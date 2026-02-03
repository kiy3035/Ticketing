package com.inyoung.ticketing.payment.repository;

import java.util.Optional;
import com.inyoung.ticketing.payment.domain.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

// 결제 리포지토리
public interface PaymentRepository extends JpaRepository<Payment, Long> {
	Optional<Payment> findByPaymentKey(String paymentKey);

	Optional<Payment> findByHoldToken(String holdToken);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Payment> findWithLockByPaymentKey(String paymentKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Payment> findWithLockByHoldToken(String holdToken);
}
