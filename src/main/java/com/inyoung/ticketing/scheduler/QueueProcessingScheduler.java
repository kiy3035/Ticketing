package com.inyoung.ticketing.scheduler;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.concert.domain.Concert;
import com.inyoung.ticketing.concert.repository.ConcertRepository;
import com.inyoung.ticketing.lock.LockService;
import com.inyoung.ticketing.queue.service.QueueService;
import com.inyoung.ticketing.seat.domain.SeatStatus;
import com.inyoung.ticketing.seat.repository.SeatRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 대기열에서 순번이 된 사용자들을 입장 허용하는 스케줄러. 분산 락으로 다중 인스턴스 시 한 노드만 실행.
// 2초마다 상위 50명 일괄 처리. 좌석 수가 50보다 적으면 예매 가능 좌석 수만큼만 입장 허용.
@Component
public class QueueProcessingScheduler {

	private static final String LOCK_KEY = "lock:batch:queue-process";
	private static final String BATCH_NAME = "queue-process";
	private static final Duration LOCK_TTL = Duration.ofSeconds(15);
	private static final Logger log = LoggerFactory.getLogger(QueueProcessingScheduler.class);

	private final QueueService queueService;
	private final ConcertRepository concertRepository;
	private final SeatRepository seatRepository;
	private final TicketingProperties properties;
	private final LockService lockService;
	private final Timer runTimer;
	private final Counter successCounter;
	private final Counter failureCounter;

	public QueueProcessingScheduler(
		QueueService queueService,
		ConcertRepository concertRepository,
		SeatRepository seatRepository,
		TicketingProperties properties,
		LockService lockService,
		MeterRegistry registry
	) {
		this.queueService = queueService;
		this.concertRepository = concertRepository;
		this.seatRepository = seatRepository;
		this.properties = properties;
		this.lockService = lockService;
		this.runTimer = Timer.builder("ticketing_batch_run_duration_seconds")
			.tag("batch", BATCH_NAME)
			.description("Queue process batch run duration")
			.register(registry);
		this.successCounter = Counter.builder("ticketing_batch_run_total")
			.tag("batch", BATCH_NAME).tag("status", "success")
			.description("Batch run success count")
			.register(registry);
		this.failureCounter = Counter.builder("ticketing_batch_run_total")
			.tag("batch", BATCH_NAME).tag("status", "failure")
			.description("Batch run failure count")
			.register(registry);
	}

	/**
	 * 2초마다 각 콘서트별 대기열 상위 N명을 입장 허용.
	 * 좌석 개수가 배치 크기(50)보다 적으면 예매 가능 좌석 수만큼만 입장 제한하여 허용.
	 */
	@Scheduled(fixedDelayString = "${ticketing.queue.processing-interval-ms:2000}")
	public void processQueue() {
		Optional<String> lockToken = lockService.tryLock(LOCK_KEY, LOCK_TTL);
		if (lockToken.isEmpty()) {
			return;
		}
		Timer.Sample sample = Timer.start();
		try {
			doProcessQueue();
			successCounter.increment();
		} catch (Exception e) {
			failureCounter.increment();
			log.warn("Queue process batch failed", e);
		} finally {
			sample.stop(runTimer);
			lockService.unlock(LOCK_KEY, lockToken.get());
		}
	}

	private void doProcessQueue() {
		int batchSize = properties.getQueue().getBatchSize();

		// 모든 콘서트 조회
		List<Concert> concerts = concertRepository.findAll();

		for (Concert concert : concerts) {
			Long concertId = concert.getId();

			// 예매 가능 좌석 수 = 전체 - 이미 예매 완료(RESERVED). 이 수를 초과하여 입장 허용하지 않음.
			long totalSeats = seatRepository.countByConcertId(concertId);
			long reservedCount = seatRepository.countByConcertIdAndStatus(concertId, SeatStatus.RESERVED);
			long availableSeats = Math.max(0, totalSeats - reservedCount);
			// 좌석이 0개인 공연(미등록)은 대기열만 통과시키기 위해 배치 크기만큼 허용
			int allowCount = totalSeats == 0
				? batchSize
				: (int) Math.min(batchSize, availableSeats);

			// 대기열에서 상위 N명 조회 (배치 크기만큼)
			List<String> topTokens = queueService.getTopTokens(concertId, batchSize);

			int allowed = 0;
			for (String token : topTokens) {
				if (allowed >= allowCount) {
					break; // 입장 허용 한도 도달
				}
				// 이미 입장 허용되었는지 확인
				Optional<Long> allowedConcertId = queueService.isAllowed(token);
				if (allowedConcertId.isPresent()) {
					continue; // 이미 허용됨
				}

				// 토큰 정보 확인
				Optional<QueueService.QueueTokenData> tokenData = queueService.getTokenData(token);
				if (tokenData.isEmpty()) {
					continue; // 토큰이 없음
				}

				// 콘서트 ID 일치 확인
				if (!tokenData.get().getConcertId().equals(concertId)) {
					continue; // 다른 콘서트 토큰
				}

				// 입장 허용 상태 설정
				queueService.allowEntry(token, concertId);
				allowed++;
			}
		}
	}
}
