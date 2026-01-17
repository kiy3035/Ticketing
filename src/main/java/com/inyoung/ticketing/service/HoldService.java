package com.inyoung.ticketing.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.domain.Seat;
import com.inyoung.ticketing.domain.SeatHold;
import com.inyoung.ticketing.domain.SeatStatus;
import com.inyoung.ticketing.dto.HoldCreateRequest;
import com.inyoung.ticketing.dto.HoldResponse;
import com.inyoung.ticketing.lock.LockService;
import com.inyoung.ticketing.repository.SeatHoldRepository;
import com.inyoung.ticketing.repository.SeatRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HoldService {
	private final SeatRepository seatRepository;
	private final SeatHoldRepository seatHoldRepository;
	private final LockService lockService;
	private final TicketingProperties properties;

	public HoldService(
		SeatRepository seatRepository,
		SeatHoldRepository seatHoldRepository,
		LockService lockService,
		TicketingProperties properties
	) {
		this.seatRepository = seatRepository;
		this.seatHoldRepository = seatHoldRepository;
		this.lockService = lockService;
		this.properties = properties;
	}

	@Transactional
	public HoldResponse createHold(HoldCreateRequest request) {
		Seat seat = seatRepository.findById(request.getSeatId())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Seat not found"));

		if (!seat.getConcert().getId().equals(request.getConcertId())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Seat does not belong to concert");
		}

		String lockKey = "lock:seat:" + seat.getId();
		Optional<String> lockToken = lockService.tryLock(lockKey, Duration.ofSeconds(5));
		if (lockToken.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Seat is busy");
		}

		try {
			if (seat.getStatus() != SeatStatus.AVAILABLE) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Seat not available");
			}

			seat.setStatus(SeatStatus.HELD);
			seatRepository.save(seat);

			SeatHold hold = new SeatHold();
			hold.setConcert(seat.getConcert());
			hold.setSeat(seat);
			hold.setUserId(request.getUserId());
			hold.setHoldToken(UUID.randomUUID().toString());
			hold.setExpiresAt(Instant.now().plusSeconds(properties.getHold().getTtlSeconds()));

			SeatHold saved = seatHoldRepository.save(hold);
			return new HoldResponse(saved);
		} finally {
			lockService.unlock(lockKey, lockToken.get());
		}
	}

	@Transactional
	public void cancelHold(Long holdId) {
		SeatHold hold = seatHoldRepository.findById(holdId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hold not found"));

		Seat seat = hold.getSeat();
		if (seat.getStatus() == SeatStatus.HELD) {
			seat.setStatus(SeatStatus.AVAILABLE);
			seatRepository.save(seat);
		}

		seatHoldRepository.delete(hold);
	}
}
