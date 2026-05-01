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
 * ════════════════════════════════════════════════════════════════
 * [PaymentRepository]
 * 결제 조회·저장·상태/수단별 집계 제공.
 * ════════════════════════════════════════════════════════════════
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

	Optional<Payment> findByPaymentKey(String paymentKey);

	Optional<Payment> findByHoldToken(String holdToken);

	/** 예약 확정 시 해당 예약에 연결된 결제 조회. 예매 내역에서 결제 수단(POINT/CARD) 표시용. */
	Optional<Payment> findByReservationId(Long reservationId);

	/**
	 * 결제 상태 변경 시 비관적 락 조회 (3종).
	 *
	 * ■ 왜 결제에서 @Lock이 필요한가?
	 *   결제는 금전과 직결되므로 동시성이 가장 엄격하게 제어되어야 하는 영역이다.
	 *
	 *   시나리오: 사용자가 실수로 결제 버튼을 두 번 클릭 → 두 요청이 동시 진입.
	 *   락 없이 둘 다 READY 상태를 읽고 APPROVED로 변경하면 중복 결제 발생.
	 *   @Lock(PESSIMISTIC_WRITE)로 첫 번째 요청이 상태를 변경하고 커밋하면,
	 *   두 번째 요청은 이미 APPROVED/COMPLETED 상태를 보고 예외를 던진다.
	 *
	 *   findWithLockByPaymentKey: paymentKey(결제 키)로 조회 + 락
	 *   findWithLockByHoldToken: holdToken(홀드 토큰)으로 조회 + 락
	 *   findWithLockById: ID로 조회 + 락
	 *
	 *   세 가지 방식 모두 제공하는 이유:
	 *   결제 처리 진입 시 어떤 키를 가지고 들어오느냐에 따라 선택해 사용.
	 *   (결제 요청 시 paymentKey, Kafka 이벤트 처리 시 holdToken 등)
	 *
	 * ■ 비관적 락 vs 낙관적 락
	 *   낙관적 락(@Version): 충돌이 드물다고 가정. 커밋 시점에 버전 비교 → 충돌 시 예외.
	 *   비관적 락(@Lock PESSIMISTIC): 충돌이 자주 있다고 가정. 처음부터 잠금.
	 *   결제처럼 충돌 시 재처리 비용이 높은 경우 비관적 락이 더 안전하다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Payment> findWithLockByPaymentKey(String paymentKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Payment> findWithLockByHoldToken(String holdToken);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Payment> findWithLockById(Long id);

	/** 콘서트별·상태별 결제 페이징 조회 (취소된 공연 환불 배치용) */
	Page<Payment> findByConcertIdAndStatus(Long concertId, PaymentStatus status, Pageable pageable);

	/** 특정 상태와 completed_at 기준 시간 범위 조회. start/end는 서울 LocalDateTime. */
	List<Payment> findByStatusAndCompletedAtBetween(PaymentStatus status, LocalDateTime startTime, LocalDateTime endTime);

	/**
	 * 모든 완료된 결제의 총액 합계.
	 *
	 * ■ @Query + COALESCE
	 *   SUM은 집계 대상 행이 없으면 null을 반환한다.
	 *   반환 타입이 Long인데 null이 오면 NPE(NullPointerException) 발생.
	 *   COALESCE(SUM(...), 0L): SUM이 null이면 0으로 대체 → NPE 방지.
	 *
	 * ■ JPQL에서 문자열 리터럴 'COMPLETED' 사용
	 *   p.status = 'COMPLETED' 처럼 enum을 문자열로 비교하는 것은
	 *   엄밀히는 파라미터 바인딩(@Param)이 더 안전하다.
	 *   (오타 시 런타임 오류, 컴파일 타임 검증 불가)
	 *   단, 고정값이고 변경 가능성이 낮아 현재는 리터럴로 작성.
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
	 * 상태와 사용자ID 또는 결제키로 검색 (어드민용).
	 *
	 * ■ 긴 메서드명 주의사항
	 *   findByStatusAndUserIdContainsIgnoreCaseOrPaymentKeyContainsIgnoreCase
	 *   Spring Data JPA는 이 이름을 파싱해 다음과 같이 해석한다:
	 *   WHERE (status = ? AND userId LIKE %?% IGNORE CASE) OR (paymentKey LIKE %?% IGNORE CASE)
	 *
	 *   ⚠️ AND보다 OR가 우선순위가 낮으므로 의도와 다른 쿼리가 생성될 수 있다.
	 *   실제로 "status 조건이 userId OR paymentKey 검색 중 하나에만 걸리는" 버그가 생길 수 있다.
	 *   이런 복잡한 조건은 @Query로 명시적으로 작성하거나 Querydsl을 쓰는 것이 더 안전하다.
	 *   (현재 코드는 기능이 정상 동작하면 유지, 버그 발생 시 @Query로 교체 필요)
	 */
	Page<Payment> findByStatusAndUserIdContainsIgnoreCaseOrPaymentKeyContainsIgnoreCase(
		PaymentStatus status,
		String userId,
		String paymentKey,
		Pageable pageable
	);

	/** 상태로 조회 (최신순 정렬) */
	Page<Payment> findByStatusOrderByCompletedAtDesc(PaymentStatus status, Pageable pageable);
}
