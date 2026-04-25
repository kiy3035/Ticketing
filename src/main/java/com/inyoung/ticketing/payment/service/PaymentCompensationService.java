package com.inyoung.ticketing.payment.service;

import java.time.LocalDateTime;
import com.inyoung.ticketing.auth.domain.Users;
import com.inyoung.ticketing.auth.repository.UsersRepository;
import com.inyoung.ticketing.payment.domain.Payment;
import com.inyoung.ticketing.payment.domain.PaymentMethod;
import com.inyoung.ticketing.payment.domain.PaymentStatus;
import com.inyoung.ticketing.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * ══════════════════════════════════════════════════════
 * Saga 패턴 — 보상 트랜잭션(Compensating Transaction) 담당.
 * ══════════════════════════════════════════════════════
 *
 * Saga 패턴이란?
 * ──────────────────────────────────────────────────────
 * 여러 단계로 이루어진 비즈니스 흐름에서,
 * 중간 단계가 실패했을 때 이미 완료된 앞 단계를 되돌리는 패턴이다.
 *
 * 이 프로젝트의 결제 흐름 (3단계):
 *   1단계) approvePayment()  : 결제 승인 → 포인트 차감 or 토스 PG 승인
 *   2단계) completePayment() : 예약 확정 → DB에 Reservation 저장
 *   3단계) (이벤트 발행)     : Kafka로 알림
 *
 * 문제 상황:
 *   1단계에서 포인트를 차감하고 APPROVED 상태로 저장했는데,
 *   2단계(예약 확정)에서 예외가 발생하면?
 *   → @Transactional 롤백으로 2단계 DB 변경은 취소된다.
 *   → 하지만 1단계 포인트 차감은 이미 별도 트랜잭션에서 커밋됐으므로 롤백 불가.
 *   → 포인트는 빠졌는데 예약은 없는 상태가 된다.
 *
 * 해결 (보상 트랜잭션):
 *   2단계가 실패하면 이 서비스가 "포인트 다시 돌려주기 + 결제 취소"를 수행한다.
 *   이것을 "보상(compensation)"이라 한다.
 *
 * ──────────────────────────────────────────────────────
 * REQUIRES_NEW를 왜 쓰나?
 * ──────────────────────────────────────────────────────
 *
 * completePayment()가 @Transactional(outer tx)로 실행 중이다.
 * 거기서 예외가 발생하면 outer tx는 롤백 예정 상태가 된다.
 *
 * 만약 보상 코드가 같은 트랜잭션 안에서 실행되면:
 *   보상 코드가 "포인트 환불, 결제 CANCELED"를 저장해도
 *   outer tx 롤백 시 보상 결과까지 함께 롤백된다. → 보상이 의미없어짐.
 *
 * REQUIRES_NEW:
 *   기존 트랜잭션과 완전히 독립된 새 트랜잭션을 시작한다.
 *   outer tx가 롤백되어도 보상 트랜잭션은 별도로 커밋된다.
 *   → "포인트 환불 + 결제 CANCELED"가 DB에 반드시 반영됨.
 */
@Service
public class PaymentCompensationService {
	private static final Logger log = LoggerFactory.getLogger(PaymentCompensationService.class);

	private final PaymentRepository paymentRepository;
	private final UsersRepository usersRepository;

	public PaymentCompensationService(PaymentRepository paymentRepository, UsersRepository usersRepository) {
		this.paymentRepository = paymentRepository;
		this.usersRepository = usersRepository;
	}

	/**
	 * 예약 확정 실패 시 결제를 원상 복구하는 보상 트랜잭션.
	 *
	 * PaymentService.completePayment()의 catch 블록에서 호출된다.
	 * outer tx(completePayment의 @Transactional)와 독립된 새 트랜잭션으로 실행된다.
	 *
	 * 처리 내용:
	 *   POINT 결제: 차감된 포인트를 다시 사용자에게 돌려준다.
	 *   CARD  결제: 현재는 DB 상태만 CANCELED로 변경한다.
	 *               (실 운영에서는 Toss 취소 API를 호출해야 함 — 샌드박스 환경 TODO)
	 *
	 * @param paymentId 보상할 결제의 DB ID
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void compensateAfterReservationFailure(Long paymentId) {
		// 비관적 락(PESSIMISTIC_WRITE)으로 조회: 동시에 다른 트랜잭션이 같은 결제를 수정하지 못하게 막는다.
		Payment payment = paymentRepository.findWithLockById(paymentId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));

		// 이미 취소됐으면 중복 보상을 방지하기 위해 그냥 리턴 (멱등성 보장)
		if (payment.getStatus() == PaymentStatus.CANCELED) {
			return;
		}

		// APPROVED 상태가 아닌 결제는 보상 대상이 아니다.
		// (예: COMPLETED는 이미 성공한 것, READY는 아직 승인 전)
		if (payment.getStatus() != PaymentStatus.APPROVED) {
			log.warn(
				"보상 스킵: APPROVED 상태가 아님 paymentId={}, status={}",
				paymentId,
				payment.getStatus()
			);
			return;
		}

		// POINT 결제: 차감된 포인트를 돌려준다.
		// CARD 결제: Toss PG 취소 API 호출이 필요하지만 샌드박스에서는 DB 상태만 변경.
		if (payment.getPaymentMethod() == PaymentMethod.POINT) {
			refundPoints(payment.getUserId(), payment.getAmount());
			log.info("보상 완료: 포인트 환불 {}원, userId={}", payment.getAmount(), payment.getUserId());
		}

		// 결제 상태를 CANCELED로 변경.
		// REQUIRES_NEW 트랜잭션으로 커밋되므로 outer tx 롤백과 무관하게 DB에 반영된다.
		payment.setStatus(PaymentStatus.CANCELED);
		payment.setCanceledAt(LocalDateTime.now().withNano(0));
	}

	/**
	 * 포인트 환불: 사용자 포인트 잔액에 amount를 더한다.
	 * 비관적 락으로 조회해 동시 환불 시 잔액 계산 오류를 방지한다.
	 */
	private void refundPoints(String userId, Long amount) {
		Users account = usersRepository.findWithLockByUsername(userId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
		long currentPoint = account.getPoint() == null ? 0L : account.getPoint();
		account.setPoint(currentPoint + amount);
	}
}
