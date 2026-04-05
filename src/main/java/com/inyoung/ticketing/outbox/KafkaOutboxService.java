package com.inyoung.ticketing.outbox;

import java.time.Instant;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.hold.event.SeatHoldEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional Outbox 패턴의 "적재" 측.
 * <ul>
 *   <li>호출은 반드시 같은 {@code @Transactional} 경계 안에서 — 예약 row commit 과 outbox row commit 이 함께 가야 한다.</li>
 *   <li>Kafka send 는 여기서 하지 않는다. 브로커 장애가 비즈니스 DB 커밋을 막지 않게 분리한다.</li>
 *   <li>발행은 {@link KafkaOutboxPublishScheduler} 가 비동기로 수행한다.</li>
 * </ul>
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

	/** 예약 확정 등에서 호출: 현재 트랜잭션에 outbox INSERT 가 포함된다. */
	@Transactional
	public void enqueueSeatHoldEvent(SeatHoldEvent event) {
		try {
			KafkaOutbox row = new KafkaOutbox();
			row.setTopic(properties.getKafka().getHoldTopic());
			row.setPartitionKey(String.valueOf(event.getSeatId()));
			row.setPayloadJson(objectMapper.writeValueAsString(event));
			row.setStatus(KafkaOutboxStatus.PENDING);
			row.setCreatedAt(Instant.now());
			row.setPublishAttempts(0);
			repository.save(row);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Failed to serialize outbox payload", e);
		}
	}
}
