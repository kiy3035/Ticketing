package com.inyoung.ticketing.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import com.inyoung.ticketing.event.SeatHoldEventPublisher;
import com.inyoung.ticketing.event.SeatHoldEventType;
import com.inyoung.ticketing.domain.Reservation;
import com.inyoung.ticketing.domain.ReservationStatus;
import com.inyoung.ticketing.domain.Seat;
import com.inyoung.ticketing.domain.SeatStatus;
import com.inyoung.ticketing.dto.ReservationRequest;
import com.inyoung.ticketing.dto.ReservationResponse;
import com.inyoung.ticketing.hold.HoldInfo;
import com.inyoung.ticketing.hold.HoldStore;
import com.inyoung.ticketing.lock.LockService;
import com.inyoung.ticketing.repository.ReservationRepository;
import com.inyoung.ticketing.repository.SeatRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// 좌석 예약 확정 서비스
@Service
public class ReservationService {
	private final SeatRepository seatRepository;
	private final ReservationRepository reservationRepository;
	private final LockService lockService;
	private final HoldStore holdStore;
	private final SeatHoldEventPublisher eventPublisher;

	// 리포지토리/락 주입
	public ReservationService(
		SeatRepository seatRepository,
		ReservationRepository reservationRepository,
		LockService lockService,
		HoldStore holdStore,
		SeatHoldEventPublisher eventPublisher
	) {
		this.seatRepository = seatRepository;
		this.reservationRepository = reservationRepository;
		this.lockService = lockService;
		this.holdStore = holdStore;
		this.eventPublisher = eventPublisher;
	}

	@Transactional
	// 홀드를 검증하고 예약 확정 처리
	public ReservationResponse confirm(ReservationRequest request, String userId) {
		HoldInfo hold = holdStore.getHold(request.getHoldToken())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hold not found"));

		if (hold.getExpiresAt().isBefore(Instant.now())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Hold expired");
		}
		if (!hold.getUserId().equals(userId)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Hold owner mismatch");
		}

		String lockKey = "lock:seat:" + hold.getSeatId();
		Optional<String> lockToken = lockService.tryLock(lockKey, Duration.ofSeconds(5));
		if (lockToken.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Seat is busy");
		}

		try {
			if (!holdStore.isSeatHeldByToken(hold.getSeatId(), hold.getHoldToken())) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Hold expired");
			}
			Seat seat = seatRepository.findById(hold.getSeatId())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Seat not found"));
			if (!seat.getConcert().getId().equals(hold.getConcertId())) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Hold concert mismatch");
			}
			if (seat.getStatus() == SeatStatus.RESERVED) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Seat already reserved");
			}

			seat.setStatus(SeatStatus.RESERVED);
			seatRepository.save(seat);

			Reservation reservation = new Reservation();
			reservation.setConcert(seat.getConcert());
			reservation.setSeat(seat);
			reservation.setUserId(userId);
			reservation.setStatus(ReservationStatus.CONFIRMED);

			Reservation saved = reservationRepository.save(reservation);
			holdStore.releaseHold(hold.getHoldToken());
			eventPublisher.publish(SeatHoldEventType.RESERVATION_CONFIRMED, hold);

			return new ReservationResponse(saved);
		} finally {
			// 락 해제
			lockService.unlock(lockKey, lockToken.get());
		}
	}
}
