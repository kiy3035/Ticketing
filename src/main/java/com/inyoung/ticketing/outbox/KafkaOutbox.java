package com.inyoung.ticketing.outbox;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * ════════════════════════════════════════════════════════════════
 * [KafkaOutbox 엔티티 — Transactional Outbox 패턴]
 *
 * ■ Outbox 패턴이란?
 *   "DB에 저장" 과 "Kafka 발행" 을 원자적으로 처리하기 위한 패턴.
 *
 *   문제 상황:
 *   예약 확정 트랜잭션에서 DB 저장 후 Kafka에 이벤트를 발행할 때,
 *   DB 커밋은 성공했지만 Kafka 발행이 네트워크 오류 등으로 실패하면?
 *   → 예약은 확정됐지만 이후 처리(알림 전송 등)가 누락된다.
 *
 *   해결책 (Outbox 패턴):
 *   1. 예약 확정 트랜잭션 안에서 kafka_outbox 테이블에 INSERT도 함께 실행.
 *   2. 두 작업이 같은 트랜잭션 → DB 커밋 시 Outbox 행도 반드시 함께 저장됨.
 *   3. 별도 스케줄러(KafkaOutboxPublishScheduler)가 PENDING 행을 읽어 Kafka에 발행.
 *   4. 발행 성공 시 status를 PUBLISHED로 업데이트.
 *
 *   효과:
 *   - "적어도 한 번(at-least-once)" 발행 보장.
 *     DB가 남아있다면 스케줄러가 재시도하므로 이벤트 누락 없음.
 *   - DB 트랜잭션의 원자성을 활용해 메시지 브로커 장애를 우회.
 *
 *   단점:
 *   - Kafka 발행 성공 후 DB 업데이트 전 장애 시 동일 메시지가 두 번 발행될 수 있음.
 *     (중복 발행). 소비자 쪽에서 멱등성(idempotency) 처리 필요.
 *   - kafka_outbox 테이블이 일시적으로 쌓일 수 있어 주기적 정리 필요.
 *   - 스케줄러 폴링 주기만큼 발행 지연이 생길 수 있음 (실시간성 약간 저하).
 *
 * ■ BaseEntity를 상속하지 않는 이유
 *   Outbox는 생성(createdAt)만 있고 수정 개념이 없다.
 *   status, publishAttempts는 업데이트되지만 "마지막 수정 시각"이 비즈니스적으로
 *   의미 없어서 BaseEntity의 updatedAt이 불필요. createdAt만 직접 관리.
 * ════════════════════════════════════════════════════════════════
 */
@Entity
@Table(name = "kafka_outbox")
public class KafkaOutbox {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String topic;

	/**
	 * Kafka 파티션 키 (여기서는 seatId 문자열).
	 * 같은 좌석에 대한 이벤트는 동일 파티션으로 보내져 순서가 보장된다.
	 * 예: seatId=42의 HOLD 이벤트 → 동일 파티션 → HOLD 처리 후 RESERVED 처리 순서 보장.
	 */
	@Column(name = "partition_key")
	private String partitionKey;

	/**
	 * JSON 직렬화된 이벤트 페이로드 (SeatHoldEvent 등).
	 * columnDefinition = "LONGTEXT": 일반 VARCHAR보다 훨씬 큰 데이터를 저장 가능.
	 * 이벤트 페이로드 크기가 가변적이고 최대 크기를 예측하기 어려울 때 LONGTEXT 사용.
	 * 단점: LONGTEXT는 인덱싱이 안 되고, 매우 큰 데이터 저장 시 DB 부하 증가.
	 */
	@Column(name = "payload_json", nullable = false, columnDefinition = "LONGTEXT")
	private String payloadJson;

	/**
	 * Outbox 발행 상태.
	 * PENDING: 아직 발행 안 됨 (스케줄러가 처리 대상으로 인식)
	 * PUBLISHED: 발행 완료
	 * FAILED: 재시도 초과 실패
	 */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private KafkaOutboxStatus status;

	/**
	 * Outbox 행 생성 시각.
	 * Instant 사용: 이 필드는 타임존 무관한 절대 시각으로 저장.
	 * 스케줄러가 "N분 이상 PENDING인 행" 조회 시 이 값을 기준으로 필터링 가능.
	 */
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	/**
	 * Kafka 발행 시도 횟수.
	 * 스케줄러가 발행 실패 시 이 값을 증가시킨다.
	 * 최대 재시도 횟수를 초과하면 FAILED로 상태 변경 → 알람 트리거 가능.
	 */
	@Column(name = "publish_attempts", nullable = false)
	private int publishAttempts;

	/**
	 * 마지막 발행 실패 오류 메시지.
	 * 운영 중 왜 발행에 실패했는지 추적하기 위해 저장.
	 * length = 1024: 스택 트레이스 전체는 아니고 핵심 메시지만 저장.
	 */
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
