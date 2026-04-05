package com.inyoung.ticketing.outbox;

/** outbox 행 상태: 발행 대기 vs 재시도 포기(운영 수동 처리 대상). */
public enum KafkaOutboxStatus {
	PENDING,
	FAILED
}
