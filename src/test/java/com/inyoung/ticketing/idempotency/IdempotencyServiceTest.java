package com.inyoung.ticketing.idempotency;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.inyoung.ticketing.common.idempotency.IdempotencyService;
import com.inyoung.ticketing.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 멱등성 서비스 통합 테스트.
 *
 * <p><b>멱등성(Idempotency)이란?</b><br>
 * 같은 요청이 여러 번 전달되더라도 결과가 한 번만 적용되어야 한다는 성질.<br>
 * 네트워크 재시도, 사용자 중복 클릭, 브라우저 새로고침 등으로 인한 중복 처리를 방지한다.</p>
 *
 * <p><b>구현 방식 — Redis SET NX (SET if Not eXists)</b>
 * <pre>
 *   1. 첫 요청  : Redis에 키를 NX 삽입(선점) 성공 → 비즈니스 로직 수행 → 결과 저장
 *   2. 중복 요청: 같은 키가 이미 있어 NX 실패 → 저장된 결과 즉시 반환 (재실행 없음)
 *   3. 처리 실패: 키 해제(DEL) → 다음 요청이 다시 선점 가능 (재시도 허용)
 * </pre></p>
 *
 * <p>실제 Redis 컨테이너(Testcontainers)를 사용해 SET NX 원자성을 검증한다.</p>
 */
class IdempotencyServiceTest extends IntegrationTestBase {

	@Autowired private IdempotencyService idempotencyService;

	/**
	 * [시나리오] 정상 흐름: 키 선점 → 처리 중 마킹 확인 → 결과 저장 → 결과 조회
	 *
	 * <pre>
	 *   acquireKey  : Redis SET NX. 최초 호출 true, 중복 호출 false
	 *   isProcessing: 키가 선점된 상태(결과 저장 전)인지 확인 — 처리 중 여부 판별
	 *   saveResult  : 처리 완료 후 응답 결과를 Redis에 직렬화해 저장
	 *   getResult   : 저장된 결과를 역직렬화해 반환 — 중복 요청의 캐시 응답으로 사용
	 * </pre>
	 */
	@Test
	@DisplayName("멱등성 키 선점 → 결과 저장 → 동일 키 재요청 시 캐시 반환")
	void acquireAndRetrieve() {
		// System.currentTimeMillis()로 테스트마다 고유 키 생성 (병렬 실행 시 키 충돌 방지)
		String key = "test-idempotency-" + System.currentTimeMillis();
		Duration ttl = Duration.ofMinutes(1);

		// 1단계: 최초 선점 — SET NX 성공 (아무도 잡고 있지 않음)
		boolean acquired = idempotencyService.acquireKey(key, ttl);
		assertThat(acquired).as("최초 acquireKey는 true를 반환해야 한다").isTrue();

		// 2단계: 같은 키로 재선점 시도 — SET NX 실패 (키가 이미 존재)
		boolean duplicate = idempotencyService.acquireKey(key, ttl);
		assertThat(duplicate).as("중복 키는 선점 불가").isFalse();

		// 3단계: 처리 중 상태 확인 — 선점만 됐고 결과는 아직 저장 전
		assertThat(idempotencyService.isProcessing(key))
			.as("결과 저장 전에는 처리 중 상태여야 한다")
			.isTrue();

		// 4단계: 비즈니스 로직 처리 완료 후 결과 저장
		idempotencyService.saveResult(key, "success-result", ttl);

		// 5단계: 저장된 결과 조회 — 중복 요청이 오면 이 값을 그대로 돌려준다
		Optional<Object> cached = idempotencyService.getResult(key, Object.class);
		assertThat(cached)
			.as("saveResult 이후 getResult는 값을 반환해야 한다")
			.isPresent();
	}

	/**
	 * [시나리오] 처리 도중 예외 발생 → 키 해제 → 동일 키로 재시도 가능
	 *
	 * 비즈니스 로직 중 예외가 발생하면 반드시 키를 DEL 해야 한다.
	 * 해제하지 않으면 TTL이 만료될 때까지 재시도가 불가능해져 사용자가 손해를 본다.
	 * releaseKey: Redis DEL — 다음 요청이 다시 acquireKey에서 true를 받을 수 있게 된다.
	 */
	@Test
	@DisplayName("처리 실패 시 키 해제 → 재시도 가능")
	void releaseOnFailure() {
		String key = "test-idempotency-fail-" + System.currentTimeMillis();
		Duration ttl = Duration.ofMinutes(1);

		// 1단계: 첫 선점 (비즈니스 로직 처리 시작)
		idempotencyService.acquireKey(key, ttl);

		// 2단계: 처리 실패 시뮬레이션 — 키 해제 (Redis DEL)
		idempotencyService.releaseKey(key);

		// 3단계: 같은 키로 재선점 — 키가 삭제됐으므로 SET NX 다시 성공
		boolean reacquired = idempotencyService.acquireKey(key, ttl);
		assertThat(reacquired).as("해제 후 재선점 가능").isTrue();
	}

	// ── race condition ───────────────────────────────────────────────────────────

	/**
	 * [시나리오] 50개 스레드 동시 선점 시도 → 정확히 1개만 성공
	 *
	 * <b>검증 핵심: Redis SET NX 원자성(Atomicity)</b><br>
	 * 동시에 여러 요청이 와도 Redis는 딱 하나의 SET NX만 성공시킨다.
	 * JVM 레벨의 synchronized와 달리 분산 환경(서버 2대)에서도 동일하게 보장된다.
	 *
	 * <b>CountDownLatch 사용 이유</b><br>
	 * 단순 루프로 스레드를 생성하면 시차가 생겨 경쟁 상황이 재현되지 않는다.
	 * CountDownLatch로 모든 스레드를 출발선에 세운 뒤 동시에 풀어줘야
	 * 실제 race condition이 발생한다.
	 *
	 * <pre>
	 *   ready : 각 스레드가 "준비됐어요" 신호를 보내는 카운터 (모두 0이 되면 출발 신호)
	 *   start : 신호총. countDown() 한 번으로 대기 중인 모든 스레드를 동시에 풀어준다
	 *   done  : 모든 스레드 종료를 메인 스레드가 await()로 기다리는 카운터
	 * </pre>
	 */
	@Test
	@DisplayName("50개 스레드 동시 선점 시도 → 정확히 1개만 성공 (Redis SET NX 원자성)")
	void concurrentAcquire_onlyOneSucceeds() throws InterruptedException {
		int threadCount = 50;
		String key = "test-concurrent-idempotency-" + System.currentTimeMillis();
		Duration ttl = Duration.ofMinutes(1);

		CountDownLatch ready = new CountDownLatch(threadCount); // 준비 완료 카운터
		CountDownLatch start = new CountDownLatch(1);           // 동시 출발 신호총
		CountDownLatch done  = new CountDownLatch(threadCount); // 완료 대기 카운터
		// AtomicInteger: 멀티스레드 환경에서 int 증가를 CAS(Compare-And-Swap)로 안전하게 수행
		AtomicInteger successCount = new AtomicInteger(0);

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				ready.countDown();  // "나 준비 됐어요" — ready 카운터 감소
				try {
					start.await();  // 신호총이 울릴 때까지 블로킹 (동시 출발 보장)
					if (idempotencyService.acquireKey(key, ttl)) {
						successCount.incrementAndGet(); // 선점 성공한 스레드만 카운트
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown(); // "나 끝났어요" — done 카운터 감소
				}
			});
		}

		ready.await();     // 모든 스레드 준비 완료될 때까지 대기
		start.countDown(); // 신호총 발사 — 50개 스레드 동시 출발
		done.await();      // 50개 스레드 전원 종료까지 대기
		executor.shutdown();

		assertThat(successCount.get())
			.as("50개 스레드가 동시에 선점 시도해도 Redis SET NX 원자성으로 정확히 1개만 성공해야 한다")
			.isEqualTo(1);
	}
}
