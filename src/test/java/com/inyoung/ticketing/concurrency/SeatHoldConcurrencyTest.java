package com.inyoung.ticketing.concurrency;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.inyoung.ticketing.concert.domain.Concert;
import com.inyoung.ticketing.concert.domain.ConcertCategory;
import com.inyoung.ticketing.concert.domain.ConcertStatus;
import com.inyoung.ticketing.concert.repository.ConcertRepository;
import com.inyoung.ticketing.hold.dto.HoldRequest;
import com.inyoung.ticketing.hold.dto.HoldResponse;
import com.inyoung.ticketing.hold.service.HoldService;
import com.inyoung.ticketing.seat.domain.Seat;
import com.inyoung.ticketing.seat.domain.SeatStatus;
import com.inyoung.ticketing.seat.repository.SeatRepository;
import com.inyoung.ticketing.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 좌석 홀드 동시성 테스트 — 서비스 계층 E2E 검증.
 *
 * <p><b>검증 목적</b>: 100명의 사용자가 동시에 같은 좌석을 선점(홀드)하려고 할 때,
 * Redis 분산 락(SET NX) + Lua 스크립트 원자적 홀드 생성 조합으로
 * <b>정확히 1명만 성공</b>하는지 서비스 계층까지 포함해 E2E 검증한다.</p>
 *
 * <p><b>다른 동시성 테스트와의 구분</b>:
 * <ul>
 *   <li>{@link RedisLockConcurrencyTest} — Redis 락 레이어만 분리해 락 획득 원자성 검증</li>
 *   <li>{@link com.inyoung.ticketing.hold.store.HoldStoreIntegrationTest} — Lua 스크립트 원자성 검증</li>
 *   <li>이 클래스 — HoldService 전체 흐름(락 획득 → Lua 홀드 → 락 해제)을 통합해 검증</li>
 * </ul>
 *
 * <p>실제 Redis + MySQL Testcontainers 환경에서 실행한다.</p>
 */
class SeatHoldConcurrencyTest extends IntegrationTestBase {

	@Autowired private HoldService holdService;
	@Autowired private ConcertRepository concertRepository;
	@Autowired private SeatRepository seatRepository;

	private Long concertId;
	private Long seatId;

	/**
	 * 테스트마다 새 콘서트·좌석을 DB에 생성한다.
	 * 좌석 상태를 AVAILABLE 로 고정해 홀드 경합 조건을 만든다.
	 */
	@BeforeEach
	void setUp() {
		Concert concert = new Concert();
		concert.setTitle("동시성 테스트 콘서트");
		concert.setVenue("테스트홀");
		concert.setConcertAt(Instant.now().plus(Duration.ofDays(7)));
		concert.setStatus(ConcertStatus.UPCOMING);
		concert.setCategory(ConcertCategory.BAND);
		concert = concertRepository.save(concert);
		concertId = concert.getId();

		Seat seat = new Seat();
		seat.setConcert(concert);
		seat.setSection("A");
		seat.setSeatNo("1");
		seat.setPrice(50000L);
		seat.setStatus(SeatStatus.AVAILABLE);
		seat = seatRepository.save(seat);
		seatId = seat.getId();
	}

	/**
	 * 100개 스레드를 동시에 출발시켜 같은 좌석에 홀드를 시도한다.
	 *
	 * <p><b>CountDownLatch 2개 패턴 이유</b>:
	 * <ul>
	 *   <li>{@code readyLatch(100)} — 각 스레드가 준비 완료 후 countDown. 메인 스레드가
	 *       await 로 "100개 스레드가 모두 대기 중"임을 확인한 뒤 출발 신호를 보낸다.
	 *       이 단계 없이 startLatch 만 사용하면 일부 스레드가 아직 초기화 중일 때
	 *       다른 스레드가 먼저 락을 잡아 진정한 동시 경합이 성립되지 않는다.</li>
	 *   <li>{@code startLatch(1)} — countDown(1번)으로 모든 스레드가 동시에 출발.
	 *       "스타터 권총" 역할. 100개 스레드가 readyLatch 에서 await 하다가
	 *       이 latch 가 열리는 순간 한꺼번에 홀드 요청을 보낸다.</li>
	 * </ul>
	 *
	 * <p><b>AtomicInteger 사용 이유</b>: 여러 스레드에서 successCount·failCount 를 동시에
	 * 수정하므로 CAS(Compare-And-Swap) 기반의 원자적 증가가 필요하다.
	 * 일반 int 나 Integer 는 경합 상황에서 카운트가 유실될 수 있다.</p>
	 *
	 * <p><b>Future.get() 이유</b>: executor.shutdown() 만으로는 모든 태스크 완료를 보장하지 않는다.
	 * 각 Future 에 get() 을 호출해 "100개 스레드가 전부 끝났음"을 보장한 뒤 결과를 검증한다.</p>
	 */
	@Test
	@DisplayName("100명이 동시에 같은 좌석 홀드 시도 → 정확히 1명만 성공")
	void concurrentHold_onlyOneSucceeds() throws Exception {
		int threadCount = 100;
		// 스레드 수와 풀 크기를 동일하게 설정 → 100개 스레드가 즉시 실행 가능 상태
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch readyLatch = new CountDownLatch(threadCount); // 모든 스레드가 준비될 때까지 대기
		CountDownLatch startLatch = new CountDownLatch(1);           // 동시 출발 신호

		AtomicInteger successCount = new AtomicInteger(0); // 홀드 성공 횟수
		AtomicInteger failCount = new AtomicInteger(0);    // 락 실패·예외 횟수
		List<Future<?>> futures = new ArrayList<>();

		for (int i = 0; i < threadCount; i++) {
			final String userId = "user-" + i;
			futures.add(executor.submit(() -> {
				try {
					readyLatch.countDown(); // "나 준비됐어"
					startLatch.await();     // "출발 신호 기다림"

					HoldRequest request = new HoldRequest(concertId, seatId);
					HoldResponse response = holdService.createHold(request, userId);
					if (response != null) {
						successCount.incrementAndGet();
					}
				} catch (Exception e) {
					// 락 실패(429) 또는 기타 예외 → 실패로 집계
					failCount.incrementAndGet();
				}
			}));
		}

		readyLatch.await();  // 100개 스레드가 모두 준비될 때까지 대기
		startLatch.countDown(); // 동시 출발!

		// 모든 스레드가 완료될 때까지 대기 (Future.get 으로 완료 보장)
		for (Future<?> future : futures) {
			future.get();
		}
		executor.shutdown();

		assertThat(successCount.get())
			.as("동시 홀드 시도 시 정확히 1명만 성공해야 한다")
			.isEqualTo(1);
		assertThat(failCount.get())
			.as("나머지는 모두 실패해야 한다")
			.isEqualTo(threadCount - 1);
	}
}
