package com.inyoung.ticketing.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.hold.event.SeatHoldEventPublisher;
import com.inyoung.ticketing.hold.event.SeatHoldEventType;
import com.inyoung.ticketing.hold.store.HoldPayload;
import com.inyoung.ticketing.hold.store.HoldStore;
import com.inyoung.ticketing.lock.LockService;
import com.inyoung.ticketing.metrics.HoldReleaseMetrics;
import com.inyoung.ticketing.seat.service.SeatService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 만료된 홀드를 주기적으로 정리하는 스케줄러. 분산 락으로 다중 인스턴스 시 한 노드만 실행.
@Component
public class HoldCleanupScheduler {

	private static final String LOCK_KEY = "lock:batch:hold-cleanup";
	private static final String BATCH_NAME = "hold-cleanup";
	private static final Duration LOCK_TTL = Duration.ofSeconds(90);
	private static final Logger log = LoggerFactory.getLogger(HoldCleanupScheduler.class);

	private final HoldStore holdStore;
	private final SeatHoldEventPublisher eventPublisher;
	private final HoldReleaseMetrics holdReleaseMetrics;
	private final LockService lockService;
	private final TicketingProperties properties;
	private final SeatService seatService;
	private final Timer runTimer;
	private final Counter successCounter;
	private final Counter failureCounter;

	public HoldCleanupScheduler(
		HoldStore holdStore,
		SeatHoldEventPublisher eventPublisher,
		HoldReleaseMetrics holdReleaseMetrics,
		LockService lockService,
		TicketingProperties properties,
		SeatService seatService,
		MeterRegistry registry
	) {
		this.holdStore = holdStore;
		this.eventPublisher = eventPublisher;
		this.holdReleaseMetrics = holdReleaseMetrics;
		this.lockService = lockService;
		this.properties = properties;
		this.seatService = seatService;
		this.runTimer = Timer.builder("ticketing_batch_run_duration_seconds")
			.tag("batch", BATCH_NAME)
			.description("Hold cleanup batch run duration")
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

	@Scheduled(fixedDelayString = "${ticketing.hold.cleanup-interval-ms:60000}")
	// 주기적으로 Redis 만료 목록을 스캔하고 Kafka로 만료 이벤트를 발행한다.
	// - Redis는 hold:expires ZSET에 만료 시각을 저장한다.
	// - 스케줄러는 현재 시각 이전 항목을 조회하고 삭제한다.
	// - 삭제된 홀드마다 Kafka에 HOLD_EXPIRED 이벤트를 발행한다.
	public void cleanupExpiredHolds() {
		Optional<String> lockToken = lockService.tryLock(LOCK_KEY, LOCK_TTL);
		if (lockToken.isEmpty()) {
			return;
		}
		Timer.Sample sample = Timer.start();
		try {
			doCleanupExpiredHolds();
			successCounter.increment();
		} catch (Exception e) {
			failureCounter.increment();
			log.warn("Hold cleanup batch failed", e);
		} finally {
			sample.stop(runTimer);
			lockService.unlock(LOCK_KEY, lockToken.get());
		}
	}

	// 건당 Redis release + Kafka publish (I/O 대기)이므로 Virtual Thread로 병렬 처리.
	// try-with-resources가 모든 태스크 완료를 보장한다.
	private void doCleanupExpiredHolds() {
		int batchSize = properties.getHold().getCleanupBatchSize();
		List<HoldPayload> expired = holdStore.findExpiredHolds(Instant.now(), batchSize);

		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (HoldPayload payload : expired) {
				executor.submit(() -> {
					holdStore.releaseByPayload(payload.info(), payload.payload());
					holdReleaseMetrics.recordReleased("timeout");
					eventPublisher.publish(SeatHoldEventType.HOLD_EXPIRED, payload.info());
					seatService.evictAvailableSeatCount(payload.info().getConcertId());
				});
			}
		}
	}
}
