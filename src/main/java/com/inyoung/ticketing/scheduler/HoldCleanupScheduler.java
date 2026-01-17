package com.inyoung.ticketing.scheduler;

import java.time.Instant;
import java.util.List;
import com.inyoung.ticketing.domain.Seat;
import com.inyoung.ticketing.domain.SeatHold;
import com.inyoung.ticketing.domain.SeatStatus;
import com.inyoung.ticketing.repository.SeatHoldRepository;
import com.inyoung.ticketing.repository.SeatRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class HoldCleanupScheduler {
	private final SeatHoldRepository seatHoldRepository;
	private final SeatRepository seatRepository;

	public HoldCleanupScheduler(
		SeatHoldRepository seatHoldRepository,
		SeatRepository seatRepository
	) {
		this.seatHoldRepository = seatHoldRepository;
		this.seatRepository = seatRepository;
	}

	@Scheduled(fixedDelayString = "${ticketing.hold.cleanup-interval-ms:60000}")
	@Transactional
	public void cleanupExpiredHolds() {
		List<SeatHold> expired = seatHoldRepository.findByExpiresAtBefore(Instant.now());
		for (SeatHold hold : expired) {
			Seat seat = hold.getSeat();
			if (seat.getStatus() == SeatStatus.HELD) {
				seat.setStatus(SeatStatus.AVAILABLE);
				seatRepository.save(seat);
			}
			seatHoldRepository.delete(hold);
		}
	}
}
