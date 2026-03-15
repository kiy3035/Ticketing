package com.inyoung.ticketing.reservation.event;

import com.inyoung.ticketing.hold.store.HoldInfo;

/**
 * 예약 확정 완료 이벤트.
 * DB 트랜잭션 커밋 후에 발행되어, Redis 홀드 해제와 Kafka 이벤트 발행을 트랜잭션 외부에서 수행한다.
 */
public record ReservationConfirmedEvent(String holdToken, HoldInfo holdInfo) {
}
