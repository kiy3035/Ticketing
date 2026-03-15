package com.inyoung.ticketing.payment.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import com.inyoung.ticketing.payment.domain.Payment;
import com.inyoung.ticketing.payment.domain.PaymentMethod;
import com.inyoung.ticketing.payment.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 결제 리포지토리.
 * 결제 조회·저장·상태/수단별 집계 제공.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {
	Optional<Payment> findByPaymentKey(String paymentKey);

	Optional<Payment> findByHoldToken(String holdToken);

	/** 예약 확정 시 해당 예약에 연결된 결제 조회. 예매 내역에서 결제 수단(POINT/CARD) 표시용. */
	Optional<Payment> findByReservationId(Long reservationId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Payment> findWithLockByPaymentKey(String paymentKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Payment> findWithLockByHoldToken(String holdToken);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Payment> findWithLockById(Long id);

	/**
	 * 콘서트별·상태별 결제 페이징 조회 (취소된 공연 환불 배치용)
	 */
	Page<Payment> findByConcertIdAndStatus(Long concertId, PaymentStatus status, Pageable pageable);

	/**
	 * 특정 상태와 completed_at(서울 시간 DATETIME) 기준 시간 범위 조회. start/end 는 서울 LocalDateTime.
	 */
	List<Payment> findByStatusAndCompletedAtBetween(PaymentStatus status, LocalDateTime startTime, LocalDateTime endTime);

	/**
	 * 모든 완료된 결제의 총액 합계
	 */
	@Query("SELECT COALESCE(SUM(p.amount), 0L) FROM Payment p WHERE p.status = 'COMPLETED'")
	Long sumAllAmounts();

	/** 완료(COMPLETED) 결제만 대상으로 결제 수단별 금액 합계. 관리자 통계(포인트 매출 누적 / 카드 결제 누적)용. */
	@Query("SELECT COALESCE(SUM(p.amount), 0L) FROM Payment p WHERE p.status = 'COMPLETED' AND p.paymentMethod = :method")
	Long sumAmountByStatusAndPaymentMethod(@Param("method") PaymentMethod method);

	/** 콘서트별 완료 결제 금액 합계 (판매자 매출 조회용) */
	@Query("SELECT COALESCE(SUM(p.amount), 0L) FROM Payment p WHERE p.concertId = :concertId AND p.status = 'COMPLETED'")
	Long sumAmountByConcertIdAndStatus(@Param("concertId") Long concertId);

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
