package com.inyoung.ticketing.payment.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import com.inyoung.ticketing.payment.domain.Payment;
import com.inyoung.ticketing.payment.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 결제 리포지토리
 * 
 * 결제 데이터의 조회, 저장, 검색 기능을 제공합니다.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {
	Optional<Payment> findByPaymentKey(String paymentKey);

	Optional<Payment> findByHoldToken(String holdToken);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Payment> findWithLockByPaymentKey(String paymentKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Payment> findWithLockByHoldToken(String holdToken);

	/**
	 * 특정 상태와 시간 범위의 결제 조회
	 */
	List<Payment> findByStatusAndCompletedAtBetween(PaymentStatus status, OffsetDateTime startTime, OffsetDateTime endTime);

	/**
	 * 모든 완료된 결제의 총액 합계
	 */
	@Query("SELECT COALESCE(SUM(p.amount), 0L) FROM Payment p WHERE p.status = 'COMPLETED'")
	Long sumAllAmounts();

	/**
	 * 상태와 사용자ID 또는 결제키로 검색
	 */
	Page<Payment> findByStatusAndUserIdContainsIgnoreCaseOrPaymentKeyContainsIgnoreCase(
		PaymentStatus status,
		String userId,
		String paymentKey,
		Pageable pageable
	);

	/**
	 * 상태로 조회 (최신순 정렬)
	 */
	Page<Payment> findByStatusOrderByCompletedAtDesc(PaymentStatus status, Pageable pageable);
}
