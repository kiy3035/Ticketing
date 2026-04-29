package com.inyoung.ticketing.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import com.inyoung.ticketing.auth.domain.Users;
import com.inyoung.ticketing.auth.repository.UsersRepository;
import com.inyoung.ticketing.hold.store.HoldInfo;
import com.inyoung.ticketing.hold.store.HoldStore;
import com.inyoung.ticketing.payment.domain.Payment;
import com.inyoung.ticketing.payment.domain.PaymentMethod;
import com.inyoung.ticketing.payment.domain.PaymentStatus;
import com.inyoung.ticketing.payment.dto.PaymentRequest;
import com.inyoung.ticketing.payment.event.PaymentCompleteEventPublisher;
import com.inyoung.ticketing.payment.repository.PaymentRepository;
import com.inyoung.ticketing.reservation.dto.ReservationResponse;
import com.inyoung.ticketing.reservation.service.ReservationService;
import org.mockito.Mockito;
import com.inyoung.ticketing.seat.repository.SeatRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * PaymentService 단위 테스트.
 * 결제 승인·완료·취소 흐름의 핵심 분기를 Mockito 로 격리해 검증한다.
 * Saga 보상 호출 여부는 verify() 로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

	@Mock private PaymentRepository paymentRepository;
	@Mock private HoldStore holdStore;
	@Mock private SeatRepository seatRepository;
	@Mock private UsersRepository usersRepository;
	@Mock private ReservationService reservationService;
	@Mock private PaymentCompensationService paymentCompensationService;
	@Mock private PaymentCompleteEventPublisher paymentCompleteEventPublisher;

	private PaymentService paymentService;

	private static final String USER_ID = "user1";
	private static final String PAYMENT_KEY = "pay-key-001";
	private static final String HOLD_TOKEN = "hold-token-001";

	@BeforeEach
	void setUp() {
		paymentService = new PaymentService(
			paymentRepository, holdStore, seatRepository, usersRepository,
			reservationService, paymentCompensationService,
			paymentCompleteEventPublisher, null, null, new SimpleMeterRegistry()
		);
	}

	// ─────────────────────────────────────────────────────────────────────
	// approvePaymentWithOption — 포인트 결제 분기
	// ─────────────────────────────────────────────────────────────────────

	/**
	 * 포인트 잔액이 결제 금액보다 부족할 때 409 Conflict 가 발생해야 한다.
	 * 사용자가 결제를 시도했지만 잔액이 없는 상황 — 409 로 클라이언트에 알린다.
	 */
	@Test
	@DisplayName("포인트 부족 시 409 Conflict")
	void approvePayment_throws409_whenPointInsufficient() {
		Payment payment = readyPointPayment(50_000L);
		when(paymentRepository.findWithLockByPaymentKey(PAYMENT_KEY)).thenReturn(Optional.of(payment));

		Users user = userWithPoint(10_000L); // 잔액 부족
		when(usersRepository.findWithLockByUsername(USER_ID)).thenReturn(Optional.of(user));

		assertThatThrownBy(() -> paymentService.approvePayment(PAYMENT_KEY, USER_ID))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409))
			.hasMessageContaining("Insufficient points");
	}

	/**
	 * 이미 APPROVED 상태인 결제를 재요청해도 에러 없이 기존 결제 정보를 그대로 반환해야 한다.
	 * 네트워크 재시도 등으로 같은 요청이 두 번 오는 상황 — 멱등하게 처리한다.
	 */
	@Test
	@DisplayName("이미 APPROVED 결제 재요청 → 멱등 응답 (에러 없음)")
	void approvePayment_idempotent_whenAlreadyApproved() {
		Payment payment = approvedPayment(30_000L);
		when(paymentRepository.findWithLockByPaymentKey(PAYMENT_KEY)).thenReturn(Optional.of(payment));

		var response = paymentService.approvePayment(PAYMENT_KEY, USER_ID);

		assertThat(response).isNotNull();
		assertThat(response.getStatus()).isEqualTo(PaymentStatus.APPROVED);
		// 포인트 차감은 한 번도 일어나지 않아야 한다
		verify(usersRepository, never()).findWithLockByUsername(any());
	}

	/**
	 * 이미 CANCELED 된 결제에 대한 승인 시도는 409 를 반환해야 한다.
	 */
	@Test
	@DisplayName("CANCELED 결제 승인 시도 → 409")
	void approvePayment_throws409_whenAlreadyCanceled() {
		Payment payment = canceledPayment(30_000L);
		when(paymentRepository.findWithLockByPaymentKey(PAYMENT_KEY)).thenReturn(Optional.of(payment));

		assertThatThrownBy(() -> paymentService.approvePayment(PAYMENT_KEY, USER_ID))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
	}

	// ─────────────────────────────────────────────────────────────────────
	// completePayment — Saga 보상 연동
	// ─────────────────────────────────────────────────────────────────────

	/**
	 * completePayment 에서 예약 확정(reservationService.confirm)이 예외를 던지면
	 * 반드시 paymentCompensationService.compensateAfterReservationFailure 가 호출되어야 한다.
	 *
	 * <p>이 테스트는 Saga 보상 패턴의 "트리거 지점"을 검증한다.
	 * 실제 보상 동작(포인트 환불·CANCELED 전환)은 PaymentCompensationIntegrationTest 가 담당한다.</p>
	 */
	@Test
	@DisplayName("예약 확정 실패 시 Saga 보상 트랜잭션 호출 verify")
	void completePayment_callsCompensation_whenReservationFails() {
		Payment payment = approvedPayment(30_000L);
		when(paymentRepository.findWithLockByPaymentKey(PAYMENT_KEY)).thenReturn(Optional.of(payment));
		when(reservationService.confirm(any(), eq(USER_ID)))
			.thenThrow(new RuntimeException("예약 확정 실패 시뮬레이션"));

		assertThatThrownBy(() -> paymentService.completePayment(PAYMENT_KEY, USER_ID))
			.isInstanceOf(RuntimeException.class);

		// 보상 서비스가 반드시 호출되어야 한다
		verify(paymentCompensationService)
			.compensateAfterReservationFailure(payment.getId());
	}

	/**
	 * completePayment 가 정상 완료되면 보상 서비스는 호출되지 않아야 한다.
	 */
	@Test
	@DisplayName("예약 확정 성공 시 Saga 보상 호출 없음")
	void completePayment_noCompensation_whenReservationSucceeds() {
		Payment payment = approvedPayment(30_000L);
		when(paymentRepository.findWithLockByPaymentKey(PAYMENT_KEY)).thenReturn(Optional.of(payment));
		when(reservationService.confirm(any(), eq(USER_ID)))
			.thenReturn(Mockito.mock(ReservationResponse.class));

		paymentService.completePayment(PAYMENT_KEY, USER_ID);

		verify(paymentCompensationService, never()).compensateAfterReservationFailure(any());
	}

	// ─────────────────────────────────────────────────────────────────────
	// cancelPayment
	// ─────────────────────────────────────────────────────────────────────

	/**
	 * 이미 COMPLETED 인 결제는 취소할 수 없다 — 409.
	 * 결제·예약까지 완료된 건을 단순 취소 API 로는 되돌릴 수 없다.
	 */
	@Test
	@DisplayName("COMPLETED 결제 취소 시도 → 409")
	void cancelPayment_throws409_whenAlreadyCompleted() {
		Payment payment = completedPayment(30_000L);
		when(paymentRepository.findWithLockByPaymentKey(PAYMENT_KEY)).thenReturn(Optional.of(payment));

		assertThatThrownBy(() -> paymentService.cancelPayment(PAYMENT_KEY, USER_ID))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
	}

	/**
	 * 다른 사용자의 결제를 취소하려 하면 409 소유자 불일치.
	 */
	@Test
	@DisplayName("다른 사용자의 결제 취소 시도 → 409 소유자 불일치")
	void cancelPayment_throws409_whenOwnerMismatch() {
		Payment payment = readyPointPayment(30_000L);
		when(paymentRepository.findWithLockByPaymentKey(PAYMENT_KEY)).thenReturn(Optional.of(payment));

		assertThatThrownBy(() -> paymentService.cancelPayment(PAYMENT_KEY, "other-user"))
			.isInstanceOf(ResponseStatusException.class)
			.satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
	}

	// ─────────────────────────────────────────────────────────────────────
	// 헬퍼
	// ─────────────────────────────────────────────────────────────────────

	private Payment readyPointPayment(long amount) {
		return buildPayment(PaymentStatus.READY, PaymentMethod.POINT, amount);
	}

	private Payment approvedPayment(long amount) {
		Payment p = buildPayment(PaymentStatus.APPROVED, PaymentMethod.POINT, amount);
		p.setApprovedAt(LocalDateTime.now());
		return p;
	}

	private Payment completedPayment(long amount) {
		Payment p = buildPayment(PaymentStatus.COMPLETED, PaymentMethod.POINT, amount);
		p.setCompletedAt(LocalDateTime.now());
		return p;
	}

	private Payment canceledPayment(long amount) {
		Payment p = buildPayment(PaymentStatus.CANCELED, PaymentMethod.POINT, amount);
		p.setCanceledAt(LocalDateTime.now());
		return p;
	}

	private Payment buildPayment(PaymentStatus status, PaymentMethod method, long amount) {
		Payment p = new Payment();
		p.setPaymentKey(PAYMENT_KEY);
		p.setHoldToken(HOLD_TOKEN);
		p.setUserId(USER_ID);
		p.setConcertId(1L);
		p.setSeatId(1L);
		p.setAmount(amount);
		p.setPaymentMethod(method);
		p.setStatus(status);
		return p;
	}

	private Users userWithPoint(long point) {
		Users u = new Users();
		u.setUsername(USER_ID);
		u.setPw("hashed");
		u.setEmail("user1@test.com");
		u.setPoint(point);
		return u;
	}
}
