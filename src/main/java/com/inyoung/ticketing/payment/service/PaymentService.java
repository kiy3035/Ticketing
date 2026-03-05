package com.inyoung.ticketing.payment.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import com.inyoung.ticketing.auth.domain.Users;
import com.inyoung.ticketing.auth.repository.UsersRepository;
import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.hold.store.HoldInfo;
import com.inyoung.ticketing.hold.store.HoldStore;
import com.inyoung.ticketing.payment.domain.Payment;
import com.inyoung.ticketing.payment.domain.PaymentStatus;
import com.inyoung.ticketing.payment.dto.PaymentRequest;
import com.inyoung.ticketing.payment.dto.PaymentResponse;
import com.inyoung.ticketing.payment.event.PaymentCompleteEventPublisher;
import com.inyoung.ticketing.payment.repository.PaymentRepository;
import com.inyoung.ticketing.reservation.dto.ReservationRequest;
import com.inyoung.ticketing.reservation.dto.ReservationResponse;
import com.inyoung.ticketing.reservation.service.ReservationService;
import com.inyoung.ticketing.seat.domain.Seat;
import com.inyoung.ticketing.seat.repository.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// Mock 결제 서비스
@Service
public class PaymentService {
	private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

	private final PaymentRepository paymentRepository;
	private final HoldStore holdStore;
	private final SeatRepository seatRepository;
	private final UsersRepository usersRepository;
	private final ReservationService reservationService;
	private final PaymentCompleteEventPublisher paymentCompleteEventPublisher;
	private final TicketingProperties properties;

	public PaymentService(
		PaymentRepository paymentRepository,
		HoldStore holdStore,
		SeatRepository seatRepository,
		UsersRepository usersRepository,
		ReservationService reservationService,
		PaymentCompleteEventPublisher paymentCompleteEventPublisher,
		TicketingProperties properties
	) {
		this.paymentRepository = paymentRepository;
		this.holdStore = holdStore;
		this.seatRepository = seatRepository;
		this.usersRepository = usersRepository;
		this.reservationService = reservationService;
		this.paymentCompleteEventPublisher = paymentCompleteEventPublisher;
		this.properties = properties;
	}

	@Transactional
	public PaymentResponse requestPayment(PaymentRequest request, String userId) {
		String holdToken = request.getHoldToken();
		HoldInfo hold = loadHold(holdToken, userId);

		// 결제 진행 단계: 홀드 TTL을 20분으로 연장 (결제 완료까지 유지)
		long extensionSeconds = properties.getPayment().getHoldExtensionTtlSeconds();
		holdStore.extendHoldTtl(holdToken, Duration.ofSeconds(extensionSeconds));

		Optional<Payment> existing = paymentRepository.findWithLockByHoldToken(holdToken);
		if (existing.isPresent()) {
			return new PaymentResponse(existing.get());
		}

		Seat seat = loadSeat(hold.getSeatId(), hold.getConcertId());
		Payment payment = new Payment();
		payment.setPaymentKey(generatePaymentKey());
		payment.setHoldToken(holdToken);
		payment.setUserId(userId);
		payment.setConcertId(hold.getConcertId());
		payment.setSeatId(hold.getSeatId());
		payment.setAmount(seat.getPrice());
		payment.setStatus(PaymentStatus.READY);

		Payment saved = paymentRepository.save(payment);
		return new PaymentResponse(saved);
	}

	@Transactional
	public PaymentResponse approvePayment(String paymentKey, String userId) {
		Payment payment = paymentRepository.findWithLockByPaymentKey(paymentKey)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
		validateOwner(payment, userId);

		if (payment.getStatus() == PaymentStatus.APPROVED
			|| payment.getStatus() == PaymentStatus.COMPLETED) {
			return new PaymentResponse(payment);
		}
		if (payment.getStatus() == PaymentStatus.CANCELED) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment already canceled");
		}

		Users account = usersRepository.findWithLockByUsername(userId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
		long currentPoint = account.getPoint() == null ? 0L : account.getPoint();
		if (currentPoint < payment.getAmount()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient points");
		}
		account.setPoint(currentPoint - payment.getAmount());

		payment.setStatus(PaymentStatus.APPROVED);
		payment.setApprovedAt(now());

		return new PaymentResponse(payment);
	}

	@Transactional
	public PaymentResponse completePayment(String paymentKey, String userId) {
		Payment payment = paymentRepository.findWithLockByPaymentKey(paymentKey)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
		validateOwner(payment, userId);

		if (payment.getStatus() == PaymentStatus.COMPLETED) {
			return new PaymentResponse(payment);
		}
		if (payment.getStatus() != PaymentStatus.APPROVED) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment not approved");
		}

		ReservationRequest reservationRequest = new ReservationRequest();
		reservationRequest.setHoldToken(payment.getHoldToken());
		ReservationResponse reservation = reservationService.confirm(reservationRequest, userId);

		payment.setStatus(PaymentStatus.COMPLETED);
		payment.setCompletedAt(now());
		payment.setReservationId(reservation.getReservationId());

		// 결제 완료 이벤트 발행 (Kafka를 통해 비동기로 이메일/SMS 전송)
		paymentCompleteEventPublisher.publishPaymentComplete(
			paymentKey,
			userId,
			payment.getConcertId(),
			payment.getAmount()
		);

		return new PaymentResponse(payment);
	}

	@Transactional
	public PaymentResponse cancelPayment(String paymentKey, String userId) {
		Payment payment = paymentRepository.findWithLockByPaymentKey(paymentKey)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
		validateOwner(payment, userId);

		if (payment.getStatus() == PaymentStatus.CANCELED) {
			return new PaymentResponse(payment);
		}
		if (payment.getStatus() == PaymentStatus.COMPLETED) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment already completed");
		}

		if (payment.getStatus() == PaymentStatus.APPROVED) {
			refundPoints(userId, payment.getAmount());
		}

		payment.setStatus(PaymentStatus.CANCELED);
		payment.setCanceledAt(now());
		return new PaymentResponse(payment);
	}

	@Transactional(readOnly = true)
	public PaymentResponse getPayment(String paymentKey, String userId) {
		Payment payment = paymentRepository.findByPaymentKey(paymentKey)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
		validateOwner(payment, userId);
		return new PaymentResponse(payment);
	}

	/**
	 * 취소된 공연 환불 배치용: 완료된 결제 1건에 대해 포인트 환불, 결제 취소, 예약/좌석 해제.
	 * 이미 CANCELED이면 스킵(idempotent). COMPLETED가 아니면 false 반환.
	 */
	@Transactional
	public boolean refundCompletedPaymentForCancelledConcert(Long paymentId) {
		Payment payment = paymentRepository.findWithLockById(paymentId).orElse(null);
		if (payment == null) {
			return false;
		}
		if (payment.getStatus() == PaymentStatus.CANCELED) {
			return true; // 이미 취소됨
		}
		if (payment.getStatus() != PaymentStatus.COMPLETED) {
			return false; // 완료된 결제만 환불 대상
		}

		try {
			refundPoints(payment.getUserId(), payment.getAmount());
		} catch (Exception e) {
			log.warn("Refund points failed for paymentId={}, userId={}, continuing to cancel payment. {}", paymentId, payment.getUserId(), e.getMessage());
		}

		payment.setStatus(PaymentStatus.CANCELED);
		payment.setCanceledAt(now());
		paymentRepository.save(payment);

		if (payment.getReservationId() != null) {
			reservationService.cancelReservationForRefund(payment.getReservationId());
		}
		return true;
	}

	private HoldInfo loadHold(String holdToken, String userId) {
		HoldInfo hold = holdStore.getHold(holdToken)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hold not found"));
		if (!hold.getUserId().equals(userId)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Hold owner mismatch");
		}
		return hold;
	}

	private Seat loadSeat(Long seatId, Long concertId) {
		Seat seat = seatRepository.findById(seatId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Seat not found"));
		if (!seat.getConcert().getId().equals(concertId)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Seat concert mismatch");
		}
		return seat;
	}

	private void validateOwner(Payment payment, String userId) {
		if (!payment.getUserId().equals(userId)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment owner mismatch");
		}
	}

	private void refundPoints(String userId, Long amount) {
		Users account = usersRepository.findWithLockByUsername(userId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
		long currentPoint = account.getPoint() == null ? 0L : account.getPoint();
		account.setPoint(currentPoint + amount);
	}

	private String generatePaymentKey() {
		return UUID.randomUUID().toString();
	}

	private OffsetDateTime now() {
		return OffsetDateTime.now(ZoneId.of("Asia/Seoul"));
	}
}
