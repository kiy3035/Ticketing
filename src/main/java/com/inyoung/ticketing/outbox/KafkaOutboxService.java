package com.inyoung.ticketing.outbox;

import java.time.Instant;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.hold.event.SeatHoldEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ══════════════════════════════════════════════════════
 * Transactional Outbox 패턴 — "적재(write)" 담당.
 * ══════════════════════════════════════════════════════
 *
 * Outbox 패턴이란?
 * ──────────────────────────────────────────────────────
 * 문제 상황:
 *   예약 확정 → DB에 Reservation 저장 → Kafka에 이벤트 발행
 *   이 두 작업을 순서대로 하면 아래 문제가 생긴다.
 *
 *   케이스 A) DB 저장 성공 → Kafka 발행 실패
 *             : DB에는 예약이 있는데 SSE 알림이 안 감. 데이터 불일치.
 *
 *   케이스 B) Kafka 발행 성공 → DB 저장 실패(롤백)
 *             : SSE 알림은 갔는데 실제 예약이 없음. 더 심각한 불일치.
 *
 * 해결책 (Outbox 패턴):
 *   Kafka에 직접 보내는 대신 같은 DB 트랜잭션 안에 "보낼 메시지"를 kafka_outbox 테이블에 INSERT한다.
 *
 *   [예약 트랜잭션]
 *     ① reservation 테이블 INSERT     ─┐
 *     ② kafka_outbox 테이블 INSERT    ─┘ 같은 트랜잭션 → 같이 커밋되거나 같이 롤백됨
 *
 *   이후 KafkaOutboxPublishScheduler가 주기적으로 outbox를 읽어 Kafka에 발행하고 행을 삭제한다.
 *   → DB와 Kafka 간 "적어도 한 번(at-least-once)" 전달이 보장됨.
 *
 * ──────────────────────────────────────────────────────
 * 이 클래스의 역할 (적재 측)
 * ──────────────────────────────────────────────────────
 *   예약 확정 트랜잭션 안에서 호출되어 kafka_outbox 행을 INSERT한다.
 *   실제 Kafka 전송은 하지 않는다 → 발행은 KafkaOutboxPublishScheduler가 담당.
 */
@Service
public class KafkaOutboxService {

	private final KafkaOutboxRepository repository;
	private final ObjectMapper objectMapper;
	private final TicketingProperties properties;

	public KafkaOutboxService(
		KafkaOutboxRepository repository,
		ObjectMapper objectMapper,
		TicketingProperties properties
	) {
		this.repository = repository;
		this.objectMapper = objectMapper;
		this.properties = properties;
	}

	/**
	 * 홀드 이벤트를 outbox 테이블에 저장한다.
	 *
	 * 반드시 상위 @Transactional 안에서 호출해야 한다.
	 * 이 INSERT가 상위 트랜잭션의 커밋/롤백에 함께 묶여야 Outbox 패턴이 성립한다.
	 *
	 * 호출 순서 예시 (ReservationService.confirm):
	 *   1) reservationRepository.save(reservation)  ← DB에 예약 저장
	 *   2) kafkaOutboxService.enqueueSeatHoldEvent() ← 같은 TX에 outbox 저장
	 *   TX COMMIT → 두 행이 함께 커밋됨
	 *   → 이후 스케줄러가 outbox를 읽어 Kafka로 전송
	 *
	 * @param event Kafka로 보낼 이벤트 객체 (RESERVATION_CONFIRMED 등)
	 */
	@Transactional
	public void enqueueSeatHoldEvent(SeatHoldEvent event) {
		try {
			KafkaOutbox row = new KafkaOutbox();

			// 어떤 Kafka 토픽에 발행할지. application.properties의 ticketing.kafka.hold-topic 값.
			row.setTopic(properties.getKafka().getHoldTopic());

			// 파티션 키: 같은 seatId의 이벤트가 항상 같은 파티션으로 가게 해서 순서를 보장한다.
			row.setPartitionKey(String.valueOf(event.getSeatId()));

			// 이벤트 객체를 JSON 문자열로 직렬화해서 DB에 저장한다.
			// 나중에 스케줄러가 이 JSON을 읽어 다시 객체로 역직렬화해 Kafka로 보낸다.
			row.setPayloadJson(objectMapper.writeValueAsString(event));

			// PENDING: 아직 Kafka에 발행되지 않은 상태. 스케줄러가 이 상태를 조회한다.
			row.setStatus(KafkaOutboxStatus.PENDING);

			row.setCreatedAt(Instant.now());

			// 발행 시도 횟수. 0으로 시작해서 실패할 때마다 1씩 증가한다.
			// maxPublishAttempts(기본 25)를 넘으면 FAILED 상태로 전환된다.
			row.setPublishAttempts(0);

			repository.save(row);
		} catch (JsonProcessingException e) {
			// 직렬화 실패는 프로그래밍 오류이므로 체크 예외를 런타임 예외로 감싸 던진다.
			// 상위 트랜잭션이 롤백되어 예약도 함께 취소된다.
			throw new IllegalStateException("Failed to serialize outbox payload", e);
		}
	}
}
