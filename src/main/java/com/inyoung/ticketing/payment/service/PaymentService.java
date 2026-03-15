package com.inyoung.ticketing.payment.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import com.inyoung.ticketing.auth.domain.Users;
import com.inyoung.ticketing.auth.repository.UsersRepository;
import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.hold.store.HoldInfo;
import com.inyoung.ticketing.hold.store.HoldStore;
import com.inyoung.ticketing.payment.client.TossPaymentsClient;
import com.inyoung.ticketing.payment.domain.Payment;
import com.inyoung.ticketing.payment.domain.PaymentMethod;
import com.inyoung.ticketing.payment.domain.PaymentStatus;
import com.inyoung.ticketing.payment.dto.CardApproveRequest;
import com.inyoung.ticketing.payment.dto.PaymentRequest;
import com.inyoung.ticketing.payment.dto.PaymentResponse;
import com.inyoung.ticketing.payment.event.PaymentCompleteEventPublisher;
import com.inyoung.ticketing.payment.repository.PaymentRepository;
import com.inyoung.ticketing.reservation.dto.ReservationRequest;
import com.inyoung.ticketing.reservation.dto.ReservationResponse;
import com.inyoung.ticketing.reservation.service.ReservationService;
import com.inyoung.ticketing.seat.domain.Seat;
import com.inyoung.ticketing.seat.repository.SeatRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 결제 서비스: 포인트 결제(회원 포인트 차감) / 카드 결제(토스페이먼츠 주문서형 위젯 후 승인 API) 분기 처리.
 *
 * [흐름] request → approve → complete.
 * - request: READY Payment 생성. CARD 시 orderId 부여(위젯 requestPayment 에 사용).
 * - approve: POINT → users.point 차감 후 APPROVED. CARD → body(paymentKey, orderId, amount) 검증 후 토스 confirm 호출, tossPaymentKey 저장 후 APPROVED.
 * - complete: 예약 확정(ReservationService.confirm), COMPLETED, reservationId 저장, 결제 완료 이벤트 발행.
 */
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
	private final TossPaymentsClient tossPaymentsClient;
	private final Timer paymentCompleteTimer;

	public PaymentService(
		PaymentRepository paymentRepository,
		HoldStore holdStore,
		SeatRepository seatRepository,
		UsersRepository usersRepository,
		ReservationService reservationService,
		PaymentCompleteEventPublisher paymentCompleteEventPublisher,
		TicketingProperties properties,
		TossPaymentsClient tossPaymentsClient,
		MeterRegistry meterRegistry
	) {
		this.paymentRepository = paymentRepository;
		this.holdStore = holdStore;
		this.seatRepository = seatRepository;
		this.usersRepository = usersRepository;
		this.reservationService = reservationService;
		this.paymentCompleteEventPublisher = paymentCompleteEventPublisher;
		this.properties = properties;
		this.tossPaymentsClient = tossPaymentsClient;
		this.paymentCompleteTimer = Timer.builder("ticketing_payment_complete_duration_seconds")
			.description("Time to complete payment (request to COMPLETED)")
			.register(meterRegistry);
	}

	/**
	 * 결제 요청: hold 검증 후 READY 상태 Payment 생성. 홀드 TTL 연장.
	 * CARD 시 orderId 부여(토스 주문서형 위젯 requestPayment 에 전달할 주문 ID). 동일 holdToken 재요청 시 기존 Payment 반환.
	 */
	@Transactional
	public PaymentResponse requestPayment(PaymentRequest request, String userId) {
		String holdToken = request.getHoldToken();
		HoldInfo hold = loadHold(holdToken, userId);

		// 결제 진행 단계: 홀드 TTL 을 설정값(기본 20분)으로 연장
		long extensionSeconds = properties.getPayment().getHoldExtensionTtlSeconds();
		holdStore.extendHoldTtl(holdToken, Duration.ofSeconds(extensionSeconds));

		Optional<Payment> existing = paymentRepository.findWithLockByHoldToken(holdToken);
		if (existing.isPresent()) {
			return new PaymentResponse(existing.get());
		}

		Seat seat = loadSeat(hold.getSeatId(), hold.getConcertId());
		PaymentMethod method = request.getPaymentMethod() != null ? request.getPaymentMethod() : PaymentMethod.POINT;
		Payment payment = new Payment();
		payment.setPaymentKey(generatePaymentKey());
		payment.setHoldToken(holdToken);
		payment.setUserId(userId);
		payment.setConcertId(hold.getConcertId());
		payment.setSeatId(hold.getSeatId());
		payment.setAmount(seat.getPrice());
		payment.setPaymentMethod(method);
		payment.setStatus(PaymentStatus.READY);
		// 카드 결제 시 토스 주문 ID 부여 (6~64자, 우리 쪽·토스 양쪽 동일 값 사용)
		if (method == PaymentMethod.CARD) {
			payment.setOrderId("TICKET_" + payment.getPaymentKey().replace("-", "").substring(0, 24));
		}

		Payment saved = paymentRepository.save(payment);
		return new PaymentResponse(saved);
	}

	/** 포인트 결제 승인: body 없이 호출 시 포인트 차감 후 APPROVED */
	@Transactional
	public PaymentResponse approvePayment(String paymentKey, String userId) {
		return approvePaymentWithOption(paymentKey, userId, null);
	}

	/**
	 * 결제 승인: paymentMethod 에 따라 분기.
	 * - CARD: body(paymentKey, orderId, amount) 필수. orderId·amount 가 우리 Payment 와 일치해야 함. 토스 POST /v1/payments/confirm 호출 후 APPROVED, tossPaymentKey 저장.
	 * - POINT: body 불필요. 보유 포인트 차감 후 APPROVED. 부족 시 409.
	 */
	@Transactional
	public PaymentResponse approvePaymentWithOption(String paymentKey, String userId, CardApproveRequest cardRequest) {
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

		if (payment.getPaymentMethod() == PaymentMethod.CARD) {
			if (cardRequest == null || cardRequest.getPaymentKey() == null || cardRequest.getOrderId() == null || cardRequest.getAmount() == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Card payment requires paymentKey, orderId, amount from Toss redirect");
			}
			if (!payment.getOrderId().equals(cardRequest.getOrderId()) || !payment.getAmount().equals(cardRequest.getAmount())) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "OrderId or amount mismatch");
			}
			tossPaymentsClient.confirmPayment(cardRequest.getPaymentKey(), cardRequest.getOrderId(), cardRequest.getAmount());
			payment.setTossPaymentKey(cardRequest.getPaymentKey());
			payment.setStatus(PaymentStatus.APPROVED);
			payment.setApprovedAt(now());
			return new PaymentResponse(payment);
		}

		// POINT: 보유 포인트 차감 후 APPROVED
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
		Timer.Sample sample = Timer.start();
		try {
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
		} finally {
			sample.stop(paymentCompleteTimer);
		}
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

		// POINT 결제만 승인 후 취소 시 포인트 환불; CARD 는 토스 모의결제라 환불 로직 생략
		if (payment.getStatus() == PaymentStatus.APPROVED && payment.getPaymentMethod() == PaymentMethod.POINT) {
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
	 * 취소된 공연 환불 배치용: 완료된 결제 1건에 대해 포인트 환불(POINT만), 결제 취소, 예약/좌석 해제.
	 * CARD 결제는 포인트를 쓰지 않았으므로 환불 생략. 이미 CANCELED 이면 스킵(idempotent).
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

		if (payment.getPaymentMethod() == PaymentMethod.POINT) {
			try {
				refundPoints(payment.getUserId(), payment.getAmount());
			} catch (Exception e) {
			log.warn("Refund points failed for paymentId={}, userId={}; skipping payment cancel until points are refunded. {}", paymentId, payment.getUserId(), e.getMessage());
				return false; // 포인트 환불 실패 시 결제 취소하지 않음. 다음 배치에서 재시도.
			}
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

	/** 서울 시간 기준 현재 시각 (DB 저장용 LocalDateTime). */
	private LocalDateTime now() {
		return LocalDateTime.now().withNano(0);
	}
}
