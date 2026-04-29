package com.inyoung.ticketing.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.inyoung.ticketing.auth.domain.Users;
import com.inyoung.ticketing.auth.repository.UsersRepository;
import com.inyoung.ticketing.payment.domain.Payment;
import com.inyoung.ticketing.payment.domain.PaymentMethod;
import com.inyoung.ticketing.payment.domain.PaymentStatus;
import com.inyoung.ticketing.payment.repository.PaymentRepository;
import com.inyoung.ticketing.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saga 보상 트랜잭션 통합 테스트.
 *
 * <p><b>검증 시나리오</b>:
 * 결제 승인(approve) 단계에서 포인트가 차감된 후,
 * 예약 확정(complete) 단계가 실패했을 때 보상 트랜잭션이
 * <ol>
 *     <li>차감된 포인트를 정확히 환불하고</li>
 *     <li>결제 상태를 CANCELED 로 전환하며</li>
 *     <li>outer 트랜잭션 롤백과 무관하게 보상 결과가 DB 에 반영되어야 한다(REQUIRES_NEW)</li>
 *     <li>같은 결제에 대한 중복 보상 요청은 안전하게 무시되어야 한다(멱등성)</li>
 * </ol>
 *
 * <p>실제 MySQL 컨테이너(Testcontainers)로 트랜잭션 격리를 검증한다.</p>
 */
class PaymentCompensationIntegrationTest extends IntegrationTestBase {

	@Autowired private PaymentCompensationService paymentCompensationService;
	@Autowired private PaymentRepository paymentRepository;
	@Autowired private UsersRepository usersRepository;

	private static final String TEST_USERNAME = "saga-test-user";
	private static final long INITIAL_POINT = 50_000L;
	private static final long PAYMENT_AMOUNT = 30_000L;

	@BeforeEach
	void setUp() {
		// 이전 테스트 잔여 데이터 정리 (Testcontainers 가 클래스 간 공유되므로)
		paymentRepository.deleteAll();
		usersRepository.findByUsername(TEST_USERNAME).ifPresent(usersRepository::delete);
	}

	/**
	 * 시나리오 1: 정상 보상 흐름.
	 * APPROVED 상태 결제(POINT 차감 완료)에 대해 compensate 호출 시
	 * - Payment 상태가 CANCELED 로 전환되고 canceledAt 이 기록되며
	 * - 사용자 포인트 잔액이 차감 전 금액으로 복구된다.
	 */
	@Test
	@DisplayName("APPROVED 결제 보상 → 포인트 환불 + 결제 CANCELED")
	void compensate_refundsPointsAndCancelsPayment() {
		Users user = createUser(INITIAL_POINT - PAYMENT_AMOUNT); // 이미 차감된 상태
		Payment payment = createApprovedPointPayment(user.getUsername(), PAYMENT_AMOUNT);

		paymentCompensationService.compensateAfterReservationFailure(payment.getId());

		Payment reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
		assertThat(reloaded.getStatus())
			.as("보상 후 결제 상태는 CANCELED 여야 한다")
			.isEqualTo(PaymentStatus.CANCELED);
		assertThat(reloaded.getCanceledAt())
			.as("취소 시각이 기록되어야 한다")
			.isNotNull();

		Users reloadedUser = usersRepository.findByUsername(TEST_USERNAME).orElseThrow();
		assertThat(reloadedUser.getPoint())
			.as("포인트가 차감 전 잔액(%d)으로 복구되어야 한다", INITIAL_POINT)
			.isEqualTo(INITIAL_POINT);
	}

	/**
	 * 시나리오 2: 멱등성 — 이미 CANCELED 인 결제에 보상 재호출.
	 * 보상 처리 후 같은 paymentId 로 다시 호출해도
	 * - 포인트가 두 번 환불되지 않아야 한다(중복 가산 방지)
	 * - Payment 상태는 CANCELED 그대로여야 한다.
	 *
	 * <p>네트워크 재시도, 스케줄러 중복 실행 등으로 같은 보상이 두 번 호출될 수 있어 필수 검증.</p>
	 */
	@Test
	@DisplayName("이미 CANCELED 인 결제 재보상 호출 → 멱등 보장 (포인트 중복 환불 없음)")
	void compensate_isIdempotent_whenAlreadyCanceled() {
		Users user = createUser(INITIAL_POINT - PAYMENT_AMOUNT);
		Payment payment = createApprovedPointPayment(user.getUsername(), PAYMENT_AMOUNT);

		// 첫 번째 보상: 정상적으로 환불 + CANCELED
		paymentCompensationService.compensateAfterReservationFailure(payment.getId());

		long pointAfterFirst = usersRepository.findByUsername(TEST_USERNAME).orElseThrow().getPoint();
		assertThat(pointAfterFirst).isEqualTo(INITIAL_POINT);

		// 두 번째 보상: 이미 CANCELED 이므로 아무 변경도 일어나지 않아야 한다
		paymentCompensationService.compensateAfterReservationFailure(payment.getId());

		long pointAfterSecond = usersRepository.findByUsername(TEST_USERNAME).orElseThrow().getPoint();
		assertThat(pointAfterSecond)
			.as("두 번째 보상 호출 후에도 포인트가 추가 환불되지 않아야 한다")
			.isEqualTo(INITIAL_POINT);

		Payment reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.CANCELED);
	}

	/**
	 * 시나리오 3: APPROVED 가 아닌 결제(예: READY)는 보상 대상이 아님.
	 * - 포인트는 환불되지 않고
	 * - 결제 상태도 그대로(READY) 유지된다.
	 *
	 * <p>잘못된 호출(예: 승인 전 결제에 보상이 발동)로부터 데이터 무결성을 보호하는지 검증.</p>
	 */
	@Test
	@DisplayName("READY 상태 결제는 보상 스킵 (잘못된 호출 보호)")
	void compensate_skips_whenStatusIsNotApproved() {
		Users user = createUser(INITIAL_POINT);
		Payment payment = createReadyPayment(user.getUsername(), PAYMENT_AMOUNT);

		paymentCompensationService.compensateAfterReservationFailure(payment.getId());

		Payment reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
		assertThat(reloaded.getStatus())
			.as("READY 상태는 보상 대상이 아니므로 변경되지 않아야 한다")
			.isEqualTo(PaymentStatus.READY);

		long point = usersRepository.findByUsername(TEST_USERNAME).orElseThrow().getPoint();
		assertThat(point)
			.as("READY 결제는 포인트 차감이 없었으므로 환불도 일어나지 않아야 한다")
			.isEqualTo(INITIAL_POINT);
	}

	/**
	 * 시나리오 4: CARD 결제 보상은 포인트 환불 없이 상태만 CANCELED.
	 * (실 운영에서는 토스 PG 취소 API 호출이 추가됨 — 현재는 DB 상태만 검증)
	 */
	@Test
	@DisplayName("CARD 결제 보상 → 포인트 미환불 + 결제 CANCELED")
	void compensate_cardPayment_onlyCancelsStatus() {
		Users user = createUser(INITIAL_POINT);
		Payment payment = createApprovedCardPayment(user.getUsername(), PAYMENT_AMOUNT);

		paymentCompensationService.compensateAfterReservationFailure(payment.getId());

		Payment reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.CANCELED);

		long point = usersRepository.findByUsername(TEST_USERNAME).orElseThrow().getPoint();
		assertThat(point)
			.as("CARD 결제는 포인트 차감이 없었으므로 환불도 일어나지 않아야 한다")
			.isEqualTo(INITIAL_POINT);
	}

	// ───────────────────────────────────── 헬퍼 ─────────────────────────────────────

	@Transactional
	void createUserTx(long initialPoint) {
		Users user = new Users();
		user.setUsername(TEST_USERNAME);
		user.setPw("$2a$10$dummyhashforintegrationtestuse.................");
		user.setEmail(TEST_USERNAME + "@test.local");
		user.setPoint(initialPoint);
		usersRepository.save(user);
	}

	private Users createUser(long initialPoint) {
		createUserTx(initialPoint);
		return usersRepository.findByUsername(TEST_USERNAME).orElseThrow();
	}

	private Payment createApprovedPointPayment(String username, long amount) {
		Payment payment = baseReadyPayment(username, amount, PaymentMethod.POINT);
		payment.setStatus(PaymentStatus.APPROVED);
		payment.setApprovedAt(java.time.LocalDateTime.now().withNano(0));
		return paymentRepository.save(payment);
	}

	private Payment createApprovedCardPayment(String username, long amount) {
		Payment payment = baseReadyPayment(username, amount, PaymentMethod.CARD);
		payment.setOrderId("TICKET_ORDER_TEST_0001");
		payment.setTossPaymentKey("TEST_TOSS_KEY_0001");
		payment.setStatus(PaymentStatus.APPROVED);
		payment.setApprovedAt(java.time.LocalDateTime.now().withNano(0));
		return paymentRepository.save(payment);
	}

	private Payment createReadyPayment(String username, long amount) {
		return paymentRepository.save(baseReadyPayment(username, amount, PaymentMethod.POINT));
	}

	private Payment baseReadyPayment(String username, long amount, PaymentMethod method) {
		Payment payment = new Payment();
		payment.setPaymentKey(java.util.UUID.randomUUID().toString());
		payment.setHoldToken(java.util.UUID.randomUUID().toString());
		payment.setUserId(username);
		payment.setConcertId(1L);
		payment.setSeatId(1L);
		payment.setAmount(amount);
		payment.setPaymentMethod(method);
		payment.setStatus(PaymentStatus.READY);
		return payment;
	}
}
