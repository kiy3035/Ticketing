package com.inyoung.ticketing.hold.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import com.inyoung.ticketing.concert.domain.Concert;
import com.inyoung.ticketing.concert.domain.ConcertStatus;
import com.inyoung.ticketing.concert.repository.ConcertRepository;
import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.hold.dto.HoldItemResponse;
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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// 좌석 홀드 생성/취소 서비스
@Service
public class HoldService {
	private final SeatRepository seatRepository;
	private final ConcertRepository concertRepository;
	private final LockService lockService;
	private final TicketingProperties properties;
	private final HoldStore holdStore;
	private final SeatHoldEventPublisher eventPublisher;
	private final Counter holdCreatedCounter;
	private final Counter lockFailureCounter;

	public HoldService(
		SeatRepository seatRepository,
		ConcertRepository concertRepository,
		LockService lockService,
		TicketingProperties properties,
		HoldStore holdStore,
		SeatHoldEventPublisher eventPublisher,
		MeterRegistry meterRegistry
	) {
		this.seatRepository = seatRepository;
		this.concertRepository = concertRepository;
		this.lockService = lockService;
		this.properties = properties;
		this.holdStore = holdStore;
		this.eventPublisher = eventPublisher;
		this.holdCreatedCounter = Counter.builder("ticketing_hold_created_total")
			.tag("status", "success")
			.description("Number of seat holds created successfully")
			.register(meterRegistry);
		this.lockFailureCounter = Counter.builder("ticketing_lock_acquire_failures_total")
			.tag("operation", "hold")
			.description("Number of lock acquire failures when creating hold")
			.register(meterRegistry);
	}

	@Transactional
	// 좌석을 홀드 상태로 전환하고 홀드 토큰을 발급
	public HoldResponse createHold(HoldRequest request, String userId) {
		Seat seat = seatRepository.findById(request.getSeatId())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Seat not found"));

		if (!seat.getConcert().getId().equals(request.getConcertId())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Seat does not belong to concert");
		}
		Concert concert = seat.getConcert();
		if (concert.getStatus() == ConcertStatus.CANCELLED) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Concert is cancelled");
		}
		if (concert.getConcertAt().isBefore(Instant.now())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Past concert cannot be booked");
		}

		String lockKey = "lock:seat:" + seat.getId();
		Duration ttl = Duration.ofSeconds(properties.getLock().getTtlSeconds());
		int retryCount = Math.max(0, properties.getLock().getRetryCount());
		long retryDelayMs = Math.max(0, properties.getLock().getRetryDelayMs());

		Optional<String> lockToken = lockService.tryLock(lockKey, ttl);
		for (int i = 0; lockToken.isEmpty() && i < retryCount; i++) {
			try {
				TimeUnit.MILLISECONDS.sleep(retryDelayMs);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				lockFailureCounter.increment();
				throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Seat is busy");
			}
			lockToken = lockService.tryLock(lockKey, ttl);
		}
		if (lockToken.isEmpty()) {
			lockFailureCounter.increment();
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
				concert.getId(),
				seat.getId(),
				userId,
				expiresAt
			);
			boolean created = holdStore.createHold(info, Duration.ofSeconds(properties.getHold().getTtlSeconds()));
			if (!created) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Seat already held");
			}
			eventPublisher.publish(SeatHoldEventType.HOLD_CREATED, info);
			holdCreatedCounter.increment();
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

	/** 로그인 사용자의 예약 중인 홀드 목록 (공연·좌석 정보 포함) */
	@Transactional(readOnly = true)
	public List<HoldItemResponse> listMyHolds(String userId) {
		return holdStore.getHoldsByUser(userId).stream()
			.map(info -> {
				Concert concert = concertRepository.findById(info.getConcertId()).orElse(null);
				Seat seat = seatRepository.findById(info.getSeatId()).orElse(null);
				String title = concert != null ? concert.getTitle() : "-";
				String venue = concert != null ? concert.getVenue() : "-";
				Instant concertAt = concert != null ? concert.getConcertAt() : null;
				String section = seat != null ? seat.getSection() : "-";
				String seatNo = seat != null ? seat.getSeatNo() : "-";
				Long price = seat != null ? seat.getPrice() : 0L;
				return new HoldItemResponse(
					info.getHoldToken(),
					info.getConcertId(),
					title,
					venue,
					concertAt,
					info.getSeatId(),
					section,
					seatNo,
					price,
					info.getExpiresAt()
				);
			})
			.toList();
	}
}
