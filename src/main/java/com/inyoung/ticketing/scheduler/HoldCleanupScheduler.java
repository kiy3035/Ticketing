package com.inyoung.ticketing.scheduler;

import java.time.Instant;
import java.util.List;
import com.inyoung.ticketing.event.SeatHoldEventPublisher;
import com.inyoung.ticketing.event.SeatHoldEventType;
import com.inyoung.ticketing.hold.HoldPayload;
import com.inyoung.ticketing.hold.HoldStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 만료된 홀드를 주기적으로 정리하는 스케줄러
@Component
public class HoldCleanupScheduler {
	private final HoldStore holdStore;
	private final SeatHoldEventPublisher eventPublisher;

	// 리포지토리 주입
	public HoldCleanupScheduler(
		HoldStore holdStore,
		SeatHoldEventPublisher eventPublisher
	) {
		this.holdStore = holdStore;
		this.eventPublisher = eventPublisher;
	}

	@Scheduled(fixedDelayString = "${ticketing.hold.cleanup-interval-ms:60000}")
	// 주기적으로 Redis 만료 목록을 스캔하고 Kafka로 만료 이벤트를 발행한다.
	// - Redis는 hold:expires ZSET에 만료 시각을 저장한다.
	// - 스케줄러는 현재 시각 이전 항목을 조회하고 삭제한다.
	// - 삭제된 홀드마다 Kafka에 HOLD_EXPIRED 이벤트를 발행한다.
	public void cleanupExpiredHolds() {
		List<HoldPayload> expired = holdStore.findExpiredHolds(Instant.now(), 200);
		for (HoldPayload payload : expired) {
			holdStore.releaseByPayload(payload.info(), payload.payload());
			eventPublisher.publish(SeatHoldEventType.HOLD_EXPIRED, payload.info());
		}
	}
}
