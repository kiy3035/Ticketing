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
 * 좌석 홀드 동시성 테스트.
 *
 * <p>100명의 사용자가 동시에 같은 좌석을 선점(홀드)하려고 할 때,
 * Redis 분산 락 + Lua 스크립트 기반 원자적 홀드 생성으로
 * <b>정확히 1명만 성공</b>하는지 검증한다.</p>
 *
 * <p>이 테스트가 통과하면 동시성 제어가 올바르게 동작함을 증명한다.</p>
 */
class SeatHoldConcurrencyTest extends IntegrationTestBase {

	@Autowired private HoldService holdService;
	@Autowired private ConcertRepository concertRepository;
	@Autowired private SeatRepository seatRepository;

	private Long concertId;
	private Long seatId;

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

	@Test
	@DisplayName("100명이 동시에 같은 좌석 홀드 시도 → 정확히 1명만 성공")
	void concurrentHold_onlyOneSucceeds() throws Exception {
		int threadCount = 100;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch readyLatch = new CountDownLatch(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);

		AtomicInteger successCount = new AtomicInteger(0);
		AtomicInteger failCount = new AtomicInteger(0);
		List<Future<?>> futures = new ArrayList<>();

		for (int i = 0; i < threadCount; i++) {
			final String userId = "user-" + i;
			futures.add(executor.submit(() -> {
				try {
					readyLatch.countDown();
					startLatch.await();

					HoldRequest request = new HoldRequest();
					request.setConcertId(concertId);
					request.setSeatId(seatId);
					HoldResponse response = holdService.createHold(request, userId);
					if (response != null) {
						successCount.incrementAndGet();
					}
				} catch (Exception e) {
					failCount.incrementAndGet();
				}
			}));
		}

		readyLatch.await();
		startLatch.countDown();

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
