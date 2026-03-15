package com.inyoung.ticketing.reservation.event;

import com.inyoung.ticketing.hold.event.SeatHoldEventPublisher;
import com.inyoung.ticketing.hold.event.SeatHoldEventType;
import com.inyoung.ticketing.hold.store.HoldStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 예약 확정 후 DB 커밋이 성공했을 때만 Redis 홀드 해제 및 Kafka 이벤트 발행.
 * 트랜잭션 롤백 시에는 실행되지 않아 DB와 Redis 일관성이 유지된다.
 */
@Component
public class ReservationConfirmedEventListener {

	private final HoldStore holdStore;
	private final SeatHoldEventPublisher eventPublisher;

	public ReservationConfirmedEventListener(HoldStore holdStore, SeatHoldEventPublisher eventPublisher) {
		this.holdStore = holdStore;
		this.eventPublisher = eventPublisher;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onReservationConfirmed(ReservationConfirmedEvent event) {
		holdStore.releaseHold(event.holdToken());
		eventPublisher.publish(SeatHoldEventType.RESERVATION_CONFIRMED, event.holdInfo());
	}
}
