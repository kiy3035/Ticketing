package com.inyoung.ticketing.scheduler;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.concert.domain.Concert;
import com.inyoung.ticketing.concert.repository.ConcertRepository;
import com.inyoung.ticketing.lock.LockService;
import com.inyoung.ticketing.queue.service.QueueService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 대기열에서 만료된 토큰을 정리하는 스케줄러. 분산 락으로 다중 인스턴스 시 한 노드만 실행.
@Component
public class QueueCleanupScheduler {

	private static final String LOCK_KEY = "lock:batch:queue-cleanup";
	private static final String BATCH_NAME = "queue-cleanup";
	private static final Duration LOCK_TTL = Duration.ofSeconds(90);
	private static final Logger log = LoggerFactory.getLogger(QueueCleanupScheduler.class);

	private final QueueService queueService;
	private final ConcertRepository concertRepository;
	private final TicketingProperties properties;
	private final LockService lockService;
	private final Timer runTimer;
	private final Counter successCounter;
	private final Counter failureCounter;

	public QueueCleanupScheduler(
		QueueService queueService,
		ConcertRepository concertRepository,
		TicketingProperties properties,
		LockService lockService,
		MeterRegistry registry
	) {
		this.queueService = queueService;
		this.concertRepository = concertRepository;
		this.properties = properties;
		this.lockService = lockService;
		this.runTimer = Timer.builder("ticketing_batch_run_duration_seconds")
			.tag("batch", BATCH_NAME)
			.description("Queue cleanup batch run duration")
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

	@Scheduled(fixedDelayString = "${ticketing.queue.cleanup-interval-ms:60000}")
	// 주기적으로 각 콘서트별 대기열에서 만료된 토큰을 제거한다.
	public void cleanupExpiredQueueTokens() {
		Optional<String> lockToken = lockService.tryLock(LOCK_KEY, LOCK_TTL);
		if (lockToken.isEmpty()) {
			return;
		}
		Timer.Sample sample = Timer.start();
		try {
			doCleanupExpiredQueueTokens();
			successCounter.increment();
		} catch (Exception e) {
			failureCounter.increment();
			log.warn("Queue cleanup batch failed", e);
		} finally {
			sample.stop(runTimer);
			lockService.unlock(LOCK_KEY, lockToken.get());
		}
	}

	private void doCleanupExpiredQueueTokens() {
		int batchSize = properties.getQueue().getCleanupBatchSize();
		List<Concert> concerts = concertRepository.findAll();
		for (Concert concert : concerts) {
			queueService.pruneExpiredTokens(concert.getId(), batchSize);
		}
	}
}
