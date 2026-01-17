package com.inyoung.ticketing.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import com.inyoung.ticketing.domain.Reservation;
import com.inyoung.ticketing.domain.ReservationStatus;
import com.inyoung.ticketing.domain.Seat;
import com.inyoung.ticketing.domain.SeatHold;
import com.inyoung.ticketing.domain.SeatStatus;
import com.inyoung.ticketing.dto.ReservationRequest;
import com.inyoung.ticketing.dto.ReservationResponse;
import com.inyoung.ticketing.lock.LockService;
import com.inyoung.ticketing.repository.ReservationRepository;
import com.inyoung.ticketing.repository.SeatHoldRepository;
import com.inyoung.ticketing.repository.SeatRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReservationService {
	private final SeatHoldRepository seatHoldRepository;
	private final SeatRepository seatRepository;
	private final ReservationRepository reservationRepository;
	private final LockService lockService;

	public ReservationService(
		SeatHoldRepository seatHoldRepository,
		SeatRepository seatRepository,
		ReservationRepository reservationRepository,
		LockService lockService
	) {
		this.seatHoldRepository = seatHoldRepository;
		this.seatRepository = seatRepository;
		this.reservationRepository = reservationRepository;
		this.lockService = lockService;
	}

	@Transactional
	public ReservationResponse confirm(ReservationRequest request) {
		SeatHold hold = seatHoldRepository.findByHoldToken(request.getHoldToken())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hold not found"));

		if (hold.getExpiresAt().isBefore(Instant.now())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Hold expired");
		}

		Seat seat = hold.getSeat();
		String lockKey = "lock:seat:" + seat.getId();
		Optional<String> lockToken = lockService.tryLock(lockKey, Duration.ofSeconds(5));
		if (lockToken.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Seat is busy");
		}

		try {
			if (seat.getStatus() != SeatStatus.HELD) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Seat not held");
			}
			if (!hold.getUserId().equals(request.getUserId())) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Hold owner mismatch");
			}

			seat.setStatus(SeatStatus.RESERVED);
			seatRepository.save(seat);

			Reservation reservation = new Reservation();
			reservation.setConcert(hold.getConcert());
			reservation.setSeat(seat);
			reservation.setUserId(request.getUserId());
			reservation.setStatus(ReservationStatus.CONFIRMED);

			Reservation saved = reservationRepository.save(reservation);
			seatHoldRepository.delete(hold);

			return new ReservationResponse(saved);
		} finally {
			lockService.unlock(lockKey, lockToken.get());
		}
	}
}
