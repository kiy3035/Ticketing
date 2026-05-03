package com.inyoung.ticketing.concurrency;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.inyoung.ticketing.lock.LockService;
import com.inyoung.ticketing.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 분산 락 동시성 테스트 — 락 레이어 단독 검증.
 *
 * <p><b>검증 목적</b>: {@code LockService.tryLock} 이 내부적으로 사용하는
 * Redis {@code SET key value NX PX ttl} 명령이 정말 원자적인지 확인한다.
 * 동일 키에 대해 50개 스레드가 동시에 락을 시도할 때 <b>정확히 1개만 성공</b>해야 한다.</p>
 *
 * <p><b>다른 동시성 테스트와의 구분</b>:
 * <ul>
 *   <li>이 클래스 — Redis 락 레이어 자체의 원자성만 격리해 검증 (서비스·홀드 로직 없음)</li>
 *   <li>{@link SeatHoldConcurrencyTest} — HoldService 전체 흐름(락 + Lua + 비즈니스)을 통합 검증</li>
 * </ul>
 *
 * <p>락 레이어를 분리해 테스트하면 "서비스 버그인지, 락 자체가 깨진 건지"를 빠르게 구분할 수 있다.</p>
 */
class RedisLockConcurrencyTest extends IntegrationTestBase {

	@Autowired private LockService lockService;

	/**
	 * 50개 스레드를 동시에 출발시켜 같은 lockKey 에 tryLock 을 시도한다.
	 *
	 * <p><b>CountDownLatch 2개 패턴 이유</b>:
	 * <ul>
	 *   <li>{@code readyLatch(50)} — 각 스레드가 준비된 뒤 countDown.
	 *       메인 스레드가 await 로 "50개 스레드가 모두 대기 중"임을 확인한 뒤 출발 신호를 보낸다.
	 *       이 단계 없이 startLatch 만 사용하면 스레드 스케줄링 차이로 인해
	 *       진정한 동시 경합이 성립되지 않을 수 있다.</li>
	 *   <li>{@code startLatch(1)} — countDown 한 번으로 모든 스레드가 동시에 tryLock 호출.
	 *       "스타터 권총" 역할.</li>
	 * </ul>
	 *
	 * <p><b>TTL 10초 설정 이유</b>: 테스트가 끝나기 전에 락이 만료되어 다른 스레드가
	 * 재획득하는 상황을 막기 위해 테스트 실행 시간보다 충분히 긴 TTL 을 설정한다.</p>
	 *
	 * <p><b>exceptionCount 의미</b>: Redis 컨테이너 연결 실패·타임아웃 등 인프라 문제 발생 시
	 * successCount 가 0 이 되는데 이를 "락이 깨진 것"과 구분하기 위해 별도로 집계한다.
	 * 테스트 실패 시 assert 메시지에 예외 수를 노출해 원인을 빠르게 파악할 수 있다.</p>
	 */
	@Test
	@DisplayName("50개 스레드가 동시에 동일 키 락 시도 → 1개만 성공")
	void concurrentLock_onlyOneAcquires() throws Exception {
		int threadCount = 50;
		String lockKey = "test:lock:concurrent";
		Duration ttl = Duration.ofSeconds(10); // 테스트 실행 중 만료되지 않도록 충분한 TTL

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch readyLatch = new CountDownLatch(threadCount); // 모든 스레드가 준비될 때까지 대기
		CountDownLatch startLatch = new CountDownLatch(1);           // 동시 출발 신호

		AtomicInteger successCount = new AtomicInteger(0);   // SET NX 성공 횟수
		AtomicInteger exceptionCount = new AtomicInteger(0); // 인프라 오류 횟수 (디버그용)
		List<Future<?>> futures = new ArrayList<>();

		for (int i = 0; i < threadCount; i++) {
			futures.add(executor.submit(() -> {
				try {
					readyLatch.countDown(); // "나 준비됐어"
					startLatch.await();     // "출발 신호 기다림"

					Optional<String> token = lockService.tryLock(lockKey, ttl);
					if (token.isPresent()) {
						successCount.incrementAndGet();
					}
				} catch (Exception e) {
					// Redis 연결 실패 시 카운트. successCount=0 이면 race condition이 아닌 인프라 문제.
					exceptionCount.incrementAndGet();
				}
			}));
		}

		readyLatch.await();     // 50개 스레드가 모두 준비될 때까지 대기
		startLatch.countDown(); // 동시 출발!

		// 모든 스레드가 완료될 때까지 대기 (Future.get 으로 완료 보장)
		for (Future<?> future : futures) {
			future.get();
		}
		executor.shutdown();

		assertThat(successCount.get())
			.as("동시 락 시도 시 정확히 1개만 획득해야 한다 (예외 발생 스레드: %d)", exceptionCount.get())
			.isEqualTo(1);
	}
}
