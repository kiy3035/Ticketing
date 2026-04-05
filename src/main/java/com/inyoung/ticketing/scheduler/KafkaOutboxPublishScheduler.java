package com.inyoung.ticketing.scheduler;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.hold.event.SeatHoldEvent;
import com.inyoung.ticketing.lock.LockService;
import com.inyoung.ticketing.outbox.KafkaOutbox;
import com.inyoung.ticketing.outbox.KafkaOutboxRepository;
import com.inyoung.ticketing.outbox.KafkaOutboxStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox 의 "발행" 측: 주기적으로 PENDING 행을 읽어 Kafka 로 보낸 뒤 삭제한다.
 * <ul>
 *   <li>{@link LockService}: 앱 인스턴스가 N대여도 배치가 중복 실행되지 않게.</li>
 *   <li>{@link org.springframework.transaction.support.TransactionTemplate}: 스케줄 메서드는 프록시 밖이라
 *       {@code @Transactional} 자기호출이 안 먹히므로, 명시적 트랜잭션 콜백으로 배치 단위 커밋을 맞춘다.</li>
 *   <li>{@code send(...).get(timeout)}: 비동기 전송 완료를 기다려 "보내기 전에 행 삭제" 레이스를 줄인다.</li>
 * </ul>
 */
@Component
public class KafkaOutboxPublishScheduler {

	private static final String LOCK_KEY = "lock:batch:kafka-outbox";
	private static final String BATCH_NAME = "kafka-outbox";
	private static final Duration LOCK_TTL = Duration.ofSeconds(120);
	private static final Logger log = LoggerFactory.getLogger(KafkaOutboxPublishScheduler.class);

	private final KafkaOutboxRepository repository;
	private final ObjectMapper objectMapper;
	private final KafkaTemplate<String, SeatHoldEvent> kafkaTemplate;
	private final LockService lockService;
	private final TicketingProperties properties;
	private final TransactionTemplate transactionTemplate;
	private final Timer runTimer;
	private final Counter publishedCounter;
	private final Counter failureCounter;

	public KafkaOutboxPublishScheduler(
		KafkaOutboxRepository repository,
		ObjectMapper objectMapper,
		KafkaTemplate<String, SeatHoldEvent> kafkaTemplate,
		LockService lockService,
		TicketingProperties properties,
		TransactionTemplate transactionTemplate,
		MeterRegistry registry
	) {
		this.repository = repository;
		this.objectMapper = objectMapper;
		this.kafkaTemplate = kafkaTemplate;
		this.lockService = lockService;
		this.properties = properties;
		this.transactionTemplate = transactionTemplate;
		this.runTimer = Timer.builder("ticketing_batch_run_duration_seconds")
			.tag("batch", BATCH_NAME)
			.description("Kafka outbox publish batch duration")
			.register(registry);
		this.publishedCounter = Counter.builder("ticketing_outbox_published_total")
			.description("Outbox rows successfully published to Kafka")
			.register(registry);
		this.failureCounter = Counter.builder("ticketing_outbox_publish_failures_total")
			.description("Outbox publish failures (retried or dead)")
			.register(registry);
	}

	/** 이전 실행 종료 시점 기준 fixedDelay — 겹침 실행을 피하고 설정으로 주기 튜닝 가능 */
	@Scheduled(fixedDelayString = "${ticketing.outbox.publish-interval-ms:500}")
	public void publishPending() {
		Optional<String> lockToken = lockService.tryLock(LOCK_KEY, LOCK_TTL);
		if (lockToken.isEmpty()) {
			return;
		}
		Timer.Sample sample = Timer.start();
		try {
			transactionTemplate.executeWithoutResult(status -> processPendingBatch());
		} catch (Exception e) {
			log.warn("Kafka outbox batch failed", e);
		} finally {
			sample.stop(runTimer);
			lockService.unlock(LOCK_KEY, lockToken.get());
		}
	}

	/** 트랜잭션 안에서 실행됨: 행 단위 delete/save 가 일관되게 커밋된다. */
	private void processPendingBatch() {
		int batchSize = properties.getOutbox().getBatchSize();
		int maxAttempts = properties.getOutbox().getMaxPublishAttempts();
		List<KafkaOutbox> batch = repository.findByStatusOrderByIdAsc(
			KafkaOutboxStatus.PENDING,
			PageRequest.of(0, batchSize)
		);
		for (KafkaOutbox row : batch) {
			try {
				SeatHoldEvent event = objectMapper.readValue(row.getPayloadJson(), SeatHoldEvent.class);
				kafkaTemplate.send(row.getTopic(), row.getPartitionKey(), event)
					.get(15, TimeUnit.SECONDS);
				repository.delete(row);
				publishedCounter.increment();
			} catch (Exception e) {
				handlePublishFailure(row, maxAttempts, e);
			}
		}
	}

	/** 재시도 한도 초과 시 FAILED 로 남겨 운영자가 수동 조치할 수 있게 한다(무한 로그 스팸 방지). */
	private void handlePublishFailure(KafkaOutbox row, int maxAttempts, Exception e) {
		failureCounter.increment();
		Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
		String raw = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
		String msg = raw.length() <= 1000 ? raw : raw.substring(0, 1000);
		row.setLastError(msg);
		row.setPublishAttempts(row.getPublishAttempts() + 1);
		if (row.getPublishAttempts() >= maxAttempts) {
			row.setStatus(KafkaOutboxStatus.FAILED);
			log.error("Outbox id={} moved to FAILED after {} attempts: {}", row.getId(), maxAttempts, msg);
		}
		repository.save(row);
		if (e instanceof InterruptedException) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
