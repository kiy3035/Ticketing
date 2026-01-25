package com.inyoung.ticketing.hold.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.hold.dto.HoldRequest;
import com.inyoung.ticketing.hold.dto.HoldResponse;
import com.inyoung.ticketing.hold.event.SeatHoldEventPublisher;
import com.inyoung.ticketing.hold.event.SeatHoldEventType;
import com.inyoung.ticketing.hold.store.HoldInfo;
import com.inyoung.ticketing.hold.store.HoldStore;
import com.inyoung.ticketing.lock.LockService;
import com.inyoung.ticketing.seat.domain.Seat;
import com.inyoung.ticketing.seat.domain.SeatStatus;
import com.inyoung.ticketing.seat.repository.SeatRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// 좌석 홀드 생성/취소 서비스
@Service
public class HoldService {
	private final SeatRepository seatRepository;
	private final LockService lockService;
	private final TicketingProperties properties;
	private final HoldStore holdStore;
	private final SeatHoldEventPublisher eventPublisher;

	// 리포지토리/락/설정 주입
	public HoldService(
		SeatRepository seatRepository,
		LockService lockService,
		TicketingProperties properties,
		HoldStore holdStore,
		SeatHoldEventPublisher eventPublisher
	) {
		this.seatRepository = seatRepository;
		this.lockService = lockService;
		this.properties = properties;
		this.holdStore = holdStore;
		this.eventPublisher = eventPublisher;
	}

	@Transactional
	// 좌석을 홀드 상태로 전환하고 홀드 토큰을 발급
	public HoldResponse createHold(HoldRequest request, String userId) {
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
			if (seat.getStatus() == SeatStatus.RESERVED) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Seat already reserved");
			}
			String holdToken = UUID.randomUUID().toString();
			Instant expiresAt = Instant.now().plusSeconds(properties.getHold().getTtlSeconds());
			HoldInfo info = new HoldInfo(
				holdToken,
				seat.getConcert().getId(),
				seat.getId(),
				userId,
				expiresAt
			);
			boolean created = holdStore.createHold(info, Duration.ofSeconds(properties.getHold().getTtlSeconds()));
			if (!created) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Seat already held");
			}
			eventPublisher.publish(SeatHoldEventType.HOLD_CREATED, info);
			return new HoldResponse(holdToken, expiresAt);
		} finally {
			// 락 해제
			lockService.unlock(lockKey, lockToken.get());
		}
	}

	@Transactional
	// 홀드 취소 및 좌석 상태 복원
	public void cancelHold(String holdToken, String userId) {
		HoldInfo info = holdStore.getHold(holdToken)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hold not found"));
		if (!info.getUserId().equals(userId)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Hold owner mismatch");
		}
		holdStore.releaseHold(holdToken);
		eventPublisher.publish(SeatHoldEventType.HOLD_CANCELED, info);
	}
}
