package com.inyoung.ticketing.reservation.repository;

import java.util.Optional;
import com.inyoung.ticketing.reservation.domain.Reservation;
import com.inyoung.ticketing.reservation.domain.ReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/**
 * ════════════════════════════════════════════════════════════════
 * [ReservationRepository]
 * ════════════════════════════════════════════════════════════════
 */
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

	/**
	 * 예약 단건 조회 + 배타 락 (SELECT FOR UPDATE).
	 *
	 * ■ @Lock(LockModeType.PESSIMISTIC_WRITE)란?
	 *   비관적 락(Pessimistic Lock) — "충돌이 일어날 것을 가정하고 미리 잠근다."
	 *   SQL로는 SELECT ... FOR UPDATE.
	 *   이 쿼리를 실행하는 순간 해당 행에 배타 락이 걸린다.
	 *   다른 트랜잭션이 같은 행에 접근하면 이 트랜잭션이 커밋/롤백할 때까지 대기.
	 *
	 * ■ 왜 여기서 비관적 락이 필요한가?
	 *   환불 배치 처리에서 같은 예약을 동시에 취소하려는 두 요청이 들어오면,
	 *   락 없이 둘 다 CONFIRMED 상태를 읽고 CANCELLED로 변경하면
	 *   두 번 취소 처리(중복 환불)가 발생할 수 있다.
	 *   락을 걸면 첫 번째 트랜잭션이 취소 완료 후 커밋하면,
	 *   두 번째 트랜잭션은 락 해제 후 이미 CANCELLED인 상태를 보고 중복 처리를 막을 수 있다.
	 *
	 * ■ 효과
	 *   - 동시 취소/변경 시 데이터 정합성 보장.
	 *   - @Transactional과 반드시 함께 사용해야 한다.
	 *     (트랜잭션이 끝나야 락이 해제되기 때문)
	 *
	 * ■ 단점
	 *   - 다른 트랜잭션이 대기하므로 처리량(throughput) 감소.
	 *   - 두 트랜잭션이 서로 상대방의 락을 기다리면 데드락(Deadlock) 발생 가능.
	 *     → 락 획득 순서를 일관되게 유지하거나 timeout 설정으로 방지.
	 *   - 스케일아웃(앱 서버 2대) 환경에서도 DB 락이기 때문에 인스턴스 간 동기화 됨.
	 *     (Redis 분산 락과 달리 DB 레벨이라 추가 인프라 불필요)
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Reservation> findWithLockById(Long id);

	// 상태별 예약 개수
	long countByStatus(ReservationStatus status);

	// 사용자별 예약 내역 최신순 조회
	java.util.List<Reservation> findByUserIdOrderByReservedAtDesc(String userId);

	/**
	 * 콘서트별 예약 목록 최신순 (판매자 대시보드용).
	 *
	 * ■ findByConcert_Id... (연관 엔티티 필드 탐색)
	 *   Concert_Id → Reservation.concert(연관 엔티티)의 id 필드를 의미.
	 *   언더스코어(_)가 탐색 구분자 역할을 한다.
	 *   생성되는 SQL: WHERE r.concert_id = ?
	 *   concert_id 컬럼에 인덱스가 있으면 빠르게 조회됨.
	 */
	java.util.List<Reservation> findByConcert_IdOrderByReservedAtDesc(Long concertId);
}
