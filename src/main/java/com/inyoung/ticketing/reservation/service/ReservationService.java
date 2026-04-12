package com.inyoung.ticketing.reservation.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Collectors;
import com.inyoung.ticketing.hold.event.SeatHoldEvent;
import com.inyoung.ticketing.hold.event.SeatHoldEventType;
import com.inyoung.ticketing.hold.store.HoldInfo;
import com.inyoung.ticketing.outbox.KafkaOutboxService;
import com.inyoung.ticketing.reservation.event.ReservationConfirmedEvent;
import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.hold.store.HoldStore;
import com.inyoung.ticketing.lock.LockService;
import com.inyoung.ticketing.concert.domain.ConcertStatus;
import com.inyoung.ticketing.reservation.domain.Reservation;
import com.inyoung.ticketing.reservation.domain.ReservationStatus;
import com.inyoung.ticketing.reservation.dto.ReservationItemResponse;
import com.inyoung.ticketing.reservation.dto.ReservationRequest;
import com.inyoung.ticketing.reservation.dto.ReservationResponse;
import com.inyoung.ticketing.payment.repository.PaymentRepository;
import com.inyoung.ticketing.reservation.repository.ReservationRepository;
import com.inyoung.ticketing.seat.domain.Seat;
import com.inyoung.ticketing.seat.domain.SeatStatus;
import com.inyoung.ticketing.seat.repository.SeatRepository;
import com.inyoung.ticketing.seat.service.SeatService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// 좌석 예약 확정 서비스.
// 결제 완료 단계에서 전달된 홀드 토큰을 검증하고,
// 좌석 단위 Redis 락 + DB 트랜잭션을 조합해 좌석 상태를 RESERVED 로 확정한다.
@Service
public class ReservationService {
	private final SeatRepository seatRepository;
	private final ReservationRepository reservationRepository;
	private final LockService lockService;
	private final TicketingProperties properties;
	private final HoldStore holdStore;
	private final ApplicationEventPublisher applicationEventPublisher;
	private final KafkaOutboxService kafkaOutboxService;
	private final PaymentRepository paymentRepository;
	private final SeatService seatService;
	private final Counter lockFailureCounter;
	private final MeterRegistry meterRegistry;

	public ReservationService(
		SeatRepository seatRepository,
		ReservationRepository reservationRepository,
		LockService lockService,
		TicketingProperties properties,
		HoldStore holdStore,
		ApplicationEventPublisher applicationEventPublisher,
		KafkaOutboxService kafkaOutboxService,
		PaymentRepository paymentRepository,
		SeatService seatService,
		MeterRegistry meterRegistry
	) {
		this.seatRepository = seatRepository;
		this.reservationRepository = reservationRepository;
		this.lockService = lockService;
		this.properties = properties;
		this.holdStore = holdStore;
		this.applicationEventPublisher = applicationEventPublisher;
		this.kafkaOutboxService = kafkaOutboxService;
		this.paymentRepository = paymentRepository;
		this.seatService = seatService;
		this.lockFailureCounter = Counter.builder("ticketing_lock_acquire_failures_total")
			.tag("operation", "reservation")
			.description("Number of lock acquire failures when confirming reservation")
			.register(meterRegistry);
		this.meterRegistry = meterRegistry;
	}

	/**
	 * 사용자 예약 내역 조회. 예약별로 결제 건을 조회해 paymentMethod(POINT/CARD) 를 채워 반환.
	 * 예매 내역 화면에서 "결제수단 포인트/카드", "N포인트 차감" / "N원 카드 결제" 구분 표시용.
	 */
	@Transactional(readOnly = true)
	public java.util.List<ReservationItemResponse> listByUser(String userId) {
		return reservationRepository.findByUserIdOrderByReservedAtDesc(userId)
			.stream()
			.map(reservation -> {
				String paymentMethod = paymentRepository.findByReservationId(reservation.getId())
					.map(p -> p.getPaymentMethod() != null ? p.getPaymentMethod().name() : "POINT")
					.orElse("POINT");
				java.time.LocalDateTime reservedAt = reservation.getReservedAt();
				java.time.OffsetDateTime reservedAtOffset = reservedAt == null ? null
					: reservedAt.atZone(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime();
				return new ReservationItemResponse(
					reservation.getId(),
					reservation.getConcert().getTitle(),
					reservation.getConcert().getVenue(),
					reservation.getConcert().getConcertAt(),
					reservation.getSeat().getSection(),
					reservation.getSeat().getSeatNo(),
					reservation.getSeat().getPrice(),
					paymentMethod,
					reservation.getStatus(),
					reservedAtOffset
				);
			})
			.collect(Collectors.toList());
	}

	@Transactional
	// 홀드를 검증하고 예약 확정 처리.
	// 1) Redis 에서 홀드 정보를 조회·만료/소유자 검증
	// 2) 좌석 단위 락을 획득해 동시 예약 경쟁 차단
	// 3) 좌석/공연 상태(취소/시각/기예약 여부) 검증 후 Reservation 생성, 좌석 RESERVED 로 변경
	// 4) 커밋 후 리스너가 Redis 홀드 해제. Kafka 는 동일 트랜잭션의 outbox 적재 → 스케줄러 발행
	public ReservationResponse confirm(ReservationRequest request, String userId) {
		// record 접근자: getHoldToken() 대신 holdToken()
		HoldInfo hold = holdStore.getHold(request.holdToken())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hold not found"));

		if (hold.getExpiresAt().isBefore(Instant.now())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Hold expired");
		}
		if (!hold.getUserId().equals(userId)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Hold owner mismatch");
		}

		String lockKey = "lock:seat:" + hold.getSeatId();
		Optional<String> lockToken = lockService.tryLock(lockKey, Duration.ofSeconds(properties.getLock().getTtlSeconds()));
		if (lockToken.isEmpty()) {
			lockFailureCounter.increment();
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
			if (seat.getConcert().getStatus() == ConcertStatus.CANCELLED) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Concert is cancelled");
			}
			if (seat.getConcert().getConcertAt().isBefore(Instant.now())) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Past concert cannot be booked");
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
			meterRegistry.counter("ticketing_reservation_confirmed_total", "concert_id", String.valueOf(hold.getConcertId()))
				.increment();
			// 도메인 이벤트: 커밋 성공 시점에만 리스너 실행(AFTER_COMMIT) → Redis 홀드만 정리.
			applicationEventPublisher.publishEvent(new ReservationConfirmedEvent(hold.getHoldToken(), hold));
			// Outbox: 이 INSERT 도 지금 트랜잭션에 포함 → 예약 row 와 함께 커밋/롤백된다.
			Instant occurredAt = Instant.now();
			kafkaOutboxService.enqueueSeatHoldEvent(new SeatHoldEvent(
				SeatHoldEventType.RESERVATION_CONFIRMED,
				hold.getHoldToken(),
				hold.getConcertId(),
				hold.getSeatId(),
				hold.getUserId(),
				hold.getExpiresAt(),
				occurredAt
			));

			return new ReservationResponse(saved);
		} finally {
			lockService.unlock(lockKey, lockToken.get());
		}
	}

	/**
	 * 환불 배치용: 예약 취소 및 좌석 해제.
	 * 공연 취소 등으로 결제가 환불될 때 예약 상태를 CANCELLED로, 좌석을 AVAILABLE로 되돌린다.
	 * 동시 실행 시 일관성을 위해 PESSIMISTIC_WRITE 락으로 조회한다.
	 */
	@Transactional
	public void cancelReservationForRefund(Long reservationId) {
		if (reservationId == null) {
			return;
		}
		Optional<Reservation> opt = reservationRepository.findWithLockById(reservationId);
		if (opt.isEmpty() || opt.get().getStatus() == ReservationStatus.CANCELLED) {
			return;
		}
		Reservation reservation = opt.get();
		reservation.setStatus(ReservationStatus.CANCELLED);
		reservationRepository.save(reservation);

		Seat seat = reservation.getSeat();
		seat.setStatus(SeatStatus.AVAILABLE);
		seatRepository.save(seat);
		seatService.evictQueueStatusAvailableSeats(seat.getConcert().getId());
	}
}
