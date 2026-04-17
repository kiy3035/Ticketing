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
	 * 예약 확정 실패 시 보상 트랜잭션.
	 * outer tx 롤백과 분리하기 위해 REQUIRES_NEW 로 실행한다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void compensateAfterReservationFailure(Long paymentId) {
		Payment payment = paymentRepository.findWithLockById(paymentId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));

		if (payment.getStatus() == PaymentStatus.CANCELED) {
			return;
		}
		if (payment.getStatus() != PaymentStatus.APPROVED) {
			log.warn(
				"보상 스킵: APPROVED 상태가 아님 paymentId={}, status={}",
				paymentId,
				payment.getStatus()
			);
			return;
		}

		if (payment.getPaymentMethod() == PaymentMethod.POINT) {
			refundPoints(payment.getUserId(), payment.getAmount());
			log.info("보상 완료: 포인트 환불 {}원, userId={}", payment.getAmount(), payment.getUserId());
		}

		payment.setStatus(PaymentStatus.CANCELED);
		payment.setCanceledAt(LocalDateTime.now().withNano(0));
	}

	private void refundPoints(String userId, Long amount) {
		Users account = usersRepository.findWithLockByUsername(userId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
		long currentPoint = account.getPoint() == null ? 0L : account.getPoint();
		account.setPoint(currentPoint + amount);
	}
}
