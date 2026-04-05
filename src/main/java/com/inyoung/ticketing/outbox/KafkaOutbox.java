package com.inyoung.ticketing.outbox;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * Kafka 발행 전 DB 에만 먼저 쌓는 "outbox" 행.
 * 예약 확정 트랜잭션과 같은 커밋 경계에 insert 되므로, DB 가 남았다면 반드시 재발행할 메시지가 남는다(이중 발행 방지·재시도는 스케줄러 정책).
 */
@Entity
@Table(name = "kafka_outbox")
public class KafkaOutbox {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String topic;

	/** Kafka 파티션 키(여기서는 seatId 문자열) — 동일 좌석 이벤트 순서 보장에 사용 */
	@Column(name = "partition_key")
	private String partitionKey;

	/** {@link com.fasterxml.jackson.databind.ObjectMapper} 로 직렬화한 {@link com.inyoung.ticketing.hold.event.SeatHoldEvent} JSON */
	@Lob
	@Column(name = "payload_json", nullable = false)
	private String payloadJson;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private KafkaOutboxStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "publish_attempts", nullable = false)
	private int publishAttempts;

	@Column(name = "last_error", length = 1024)
	private String lastError;

	public Long getId() {
		return id;
	}

	public String getTopic() {
		return topic;
	}

	public void setTopic(String topic) {
		this.topic = topic;
	}

	public String getPartitionKey() {
		return partitionKey;
	}

	public void setPartitionKey(String partitionKey) {
		this.partitionKey = partitionKey;
	}

	public String getPayloadJson() {
		return payloadJson;
	}

	public void setPayloadJson(String payloadJson) {
		this.payloadJson = payloadJson;
	}

	public KafkaOutboxStatus getStatus() {
		return status;
	}

	public void setStatus(KafkaOutboxStatus status) {
		this.status = status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public int getPublishAttempts() {
		return publishAttempts;
	}

	public void setPublishAttempts(int publishAttempts) {
		this.publishAttempts = publishAttempts;
	}

	public String getLastError() {
		return lastError;
	}

	public void setLastError(String lastError) {
		this.lastError = lastError;
	}
}
