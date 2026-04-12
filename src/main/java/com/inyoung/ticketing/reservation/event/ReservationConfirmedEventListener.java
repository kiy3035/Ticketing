package com.inyoung.ticketing.reservation.event;

import com.inyoung.ticketing.hold.store.HoldStore;
import com.inyoung.ticketing.metrics.HoldReleaseMetrics;
import com.inyoung.ticketing.seat.service.SeatService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 예약 확정의 "커밋 이후" 후처리.
 * <ul>
 *   <li>{@link TransactionalEventListener}(AFTER_COMMIT): 트랜잭션이 롤백되면 호출되지 않는다.
 *       Redis 홀드 해제는 DB 예약이 확정된 뒤에만 수행해야 하므로 이 단계가 맞다.</li>
 *   <li>Kafka {@code RESERVATION_CONFIRMED} 는 같은 트랜잭션에 outbox INSERT 까지 끝난 상태이고,
 *       실제 전송은 {@link com.inyoung.ticketing.scheduler.KafkaOutboxPublishScheduler} 가 담당한다.</li>
 * </ul>
 */
@Component
public class ReservationConfirmedEventListener {

	private final HoldStore holdStore;
	private final HoldReleaseMetrics holdReleaseMetrics;
	private final SeatService seatService;

	public ReservationConfirmedEventListener(
		HoldStore holdStore,
		HoldReleaseMetrics holdReleaseMetrics,
		SeatService seatService
	) {
		this.holdStore = holdStore;
		this.holdReleaseMetrics = holdReleaseMetrics;
		this.seatService = seatService;
	}

	/** 트랜잭션 커밋 성공 후 단일 스레드에서 호출된다(동기 이벤트 디스패치 기본). */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onReservationConfirmed(ReservationConfirmedEvent event) {
		holdStore.releaseHold(event.holdToken());
		holdReleaseMetrics.recordReleased("confirmed");
		seatService.evictAvailableSeatCount(event.holdInfo().getConcertId());
	}
}
