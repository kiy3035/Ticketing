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
 * ══════════════════════════════════════════════════════
 * Transactional Outbox 패턴 — "발행(publish)" 담당.
 * ══════════════════════════════════════════════════════
 *
 * KafkaOutboxService가 DB에 "보낼 메시지"를 저장(적재)하면,
 * 이 스케줄러가 주기적으로 그 메시지를 읽어 실제로 Kafka에 전송한다.
 *
 * 전체 흐름:
 * ──────────────────────────────────────────────────────
 *  [예약 TX 커밋] kafka_outbox에 PENDING 행 INSERT
 *       ↓ (500ms 후)
 *  [이 스케줄러] PENDING 행 조회 → Kafka 전송 → 전송 성공 시 행 DELETE
 *       ↓ (실패 시)
 *  [재시도] 다음 배치에서 다시 시도. 25회 초과 시 FAILED 상태로 전환.
 *
 * ──────────────────────────────────────────────────────
 * 설계 포인트
 * ──────────────────────────────────────────────────────
 * 1. 분산 락(LockService)
 *    앱 서버 2대에서 동시에 이 스케줄러가 실행되면 같은 outbox 행을 두 번 발행할 수 있다.
 *    Redis 분산 락을 먼저 획득한 인스턴스만 실행하고, 나머지는 그냥 건너뛴다.
 *
 * 2. TransactionTemplate (명시적 트랜잭션)
 *    @Scheduled 메서드는 Spring AOP 프록시 밖에서 실행되므로
 *    @Transactional 어노테이션이 자기 자신에게 효과가 없다(자기호출 문제).
 *    대신 TransactionTemplate으로 직접 트랜잭션을 열고 닫는다.
 *
 * 3. .get(timeout) — 동기 대기
 *    kafkaTemplate.send()는 비동기라 바로 리턴된다.
 *    .get(timeout)을 호출해 Kafka 브로커가 실제로 받을 때까지 기다린다.
 *    → "전송 완료 확인 후 DB에서 삭제"하기 위해 필수.
 *    → 기다리지 않으면 전송 실패를 모르고 행을 삭제할 수 있다.
 */
@Component
public class KafkaOutboxPublishScheduler {

	// 분산 락 키: 이 이름으로 Redis에 락을 잡는다. 다른 배치 락 키와 겹치면 안 된다.
	private static final String LOCK_KEY = "lock:batch:kafka-outbox";
	private static final String BATCH_NAME = "kafka-outbox";
	// 락 유효 시간: 이 시간 안에 배치가 끝나야 한다. 너무 짧으면 배치 중에 락이 풀린다.
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
		// Grafana에서 배치 실행 시간을 모니터링하기 위한 타이머
		this.runTimer = Timer.builder("ticketing_batch_run_duration_seconds")
			.tag("batch", BATCH_NAME)
			.description("Kafka outbox publish batch duration")
			.register(registry);
		// Kafka 발행 성공 건수 카운터
		this.publishedCounter = Counter.builder("ticketing_outbox_published_total")
			.description("Outbox rows successfully published to Kafka")
			.register(registry);
		// Kafka 발행 실패 건수 카운터
		this.failureCounter = Counter.builder("ticketing_outbox_publish_failures_total")
			.description("Outbox publish failures (retried or dead)")
			.register(registry);
	}

	/**
	 * PENDING 상태의 outbox 행을 Kafka로 발행하는 메인 스케줄 메서드.
	 *
	 * fixedDelay: 이전 실행이 끝난 시점부터 다음 실행까지의 간격.
	 * fixedRate와 달리 실행이 겹치지 않는다. (500ms 기본값)
	 */
	@Scheduled(fixedDelayString = "${ticketing.outbox.publish-interval-ms:500}")
	public void publishPending() {
		// 분산 락 획득 시도. 이미 다른 인스턴스가 실행 중이면 lockToken이 비어있다.
		Optional<String> lockToken = lockService.tryLock(LOCK_KEY, LOCK_TTL);
		if (lockToken.isEmpty()) {
			// 다른 인스턴스가 이미 락을 잡고 있음 → 이번 실행은 건너뜀
			return;
		}
		Timer.Sample sample = Timer.start();
		try {
			// TransactionTemplate으로 명시적 트랜잭션 시작.
			// 람다 안의 모든 DB 작업(조회, 삭제, 업데이트)이 하나의 트랜잭션으로 묶인다.
			transactionTemplate.executeWithoutResult(status -> processPendingBatch());
		} catch (Exception e) {
			log.warn("Kafka outbox batch failed", e);
		} finally {
			// 배치 실행 시간 기록 (Grafana 모니터링용)
			sample.stop(runTimer);
			// 성공/실패와 관계없이 항상 락 해제
			lockService.unlock(LOCK_KEY, lockToken.get());
		}
	}

	/**
	 * 실제 배치 처리 로직. 트랜잭션 안에서 실행된다.
	 *
	 * 처리 흐름:
	 *   1) PENDING 상태 행을 오래된 순서(id 오름차순)로 최대 batchSize 개 조회
	 *   2) 각 행의 JSON을 SeatHoldEvent로 역직렬화
	 *   3) Kafka에 전송하고 완료를 기다림
	 *   4) 성공 시 DB에서 행 삭제 / 실패 시 handlePublishFailure() 호출
	 */
	private void processPendingBatch() {
		int batchSize = properties.getOutbox().getBatchSize();
		int maxAttempts = properties.getOutbox().getMaxPublishAttempts();

		// PENDING 행을 ID 오름차순(먼저 쌓인 것 먼저)으로 조회
		List<KafkaOutbox> batch = repository.findByStatusOrderByIdAsc(
			KafkaOutboxStatus.PENDING,
			PageRequest.of(0, batchSize)
		);

		for (KafkaOutbox row : batch) {
			try {
				// DB에 JSON으로 저장된 이벤트를 다시 객체로 역직렬화
				SeatHoldEvent event = objectMapper.readValue(row.getPayloadJson(), SeatHoldEvent.class);

				// Kafka에 전송하고, 브로커가 실제로 받을 때까지 최대 publishTimeoutSeconds 초 대기.
				// .get()을 호출하지 않으면 비동기로 처리되어 전송 실패를 모를 수 있다.
				kafkaTemplate.send(row.getTopic(), row.getPartitionKey(), event)
					.get(properties.getOutbox().getPublishTimeoutSeconds(), TimeUnit.SECONDS);

				// 전송 성공 → DB에서 행 삭제 (같은 트랜잭션 내)
				repository.delete(row);
				publishedCounter.increment();

			} catch (Exception e) {
				// 전송 실패 → 재시도 횟수 증가. 한도 초과 시 FAILED 상태로 전환.
				handlePublishFailure(row, maxAttempts, e);
			}
		}
	}

	/**
	 * Kafka 전송 실패 처리.
	 *
	 * 재시도 횟수(publishAttempts)를 1 증가시키고:
	 *   - 아직 한도 이하: PENDING 유지 → 다음 배치에서 재시도
	 *   - 한도 초과: FAILED로 변경 → 자동 재시도 중단, 운영자 수동 확인 필요
	 *
	 * 왜 무한 재시도를 하지 않나?
	 *   Kafka 브로커 자체가 죽었거나 토픽 설정이 잘못된 경우는 재시도해도 해결이 안 된다.
	 *   무한 재시도하면 DB 쿼리 폭주 + 로그 스팸이 발생한다.
	 *   따라서 일정 횟수 후 FAILED로 두고 운영자가 원인을 확인하게 한다.
	 */
	private void handlePublishFailure(KafkaOutbox row, int maxAttempts, Exception e) {
		failureCounter.increment();

		// ExecutionException은 .get() 호출 시 내부 예외를 감싸는 래퍼.
		// 실제 원인은 getCause()에 있다.
		Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
		String raw = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
		// 오류 메시지를 DB에 저장 (너무 길면 1000자로 자름)
		String msg = raw.length() <= 1000 ? raw : raw.substring(0, 1000);

		row.setLastError(msg);
		row.setPublishAttempts(row.getPublishAttempts() + 1);

		if (row.getPublishAttempts() >= maxAttempts) {
			// 재시도 한도 초과 → FAILED 상태로 변경. 스케줄러는 이 행을 더 이상 조회하지 않는다.
			row.setStatus(KafkaOutboxStatus.FAILED);
			log.error("Outbox id={} moved to FAILED after {} attempts: {}", row.getId(), maxAttempts, msg);
		}
		// PENDING 또는 FAILED 상태로 저장 (publishAttempts와 lastError 갱신 포함)
		repository.save(row);

		// InterruptedException은 스레드 중단 신호이므로 다시 interrupt 플래그를 세워야 한다.
		// 세우지 않으면 상위 코드가 중단 신호를 놓치게 된다.
		if (e instanceof InterruptedException) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
