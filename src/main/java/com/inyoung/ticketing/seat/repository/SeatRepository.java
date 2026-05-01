package com.inyoung.ticketing.seat.repository;

import java.util.List;
import com.inyoung.ticketing.seat.domain.Seat;
import com.inyoung.ticketing.seat.domain.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * ════════════════════════════════════════════════════════════════
 * [SeatRepository]
 * ════════════════════════════════════════════════════════════════
 */
public interface SeatRepository extends JpaRepository<Seat, Long> {

	// 콘서트별 좌석 목록 조회
	List<Seat> findByConcertId(Long concertId);

	/**
	 * 콘서트별 좌석 ID 목록만 조회 (프로젝션 쿼리).
	 *
	 * ■ 왜 전체 Seat 대신 ID만 가져오나?
	 *   대기열에서 "가용 좌석 수"를 계산할 때 좌석의 section, price 등은 필요 없다.
	 *   ID만 있으면 Redis에서 현재 홀드된 ID 목록과 비교해 남은 좌석 수를 계산 가능.
	 *
	 * ■ SELECT s.id FROM Seat s (JPQL 프로젝션)
	 *   - 엔티티 전체 대신 특정 필드만 조회 → 데이터 전송량 감소, 메모리 절약.
	 *   - 효과: N개 좌석의 전체 컬럼 대신 ID 컬럼 하나만 가져옴 → 성능 향상.
	 *   - 단점: 반환 타입이 엔티티가 아닌 원시 타입(Long)이라
	 *           더 복잡한 프로젝션(여러 필드)은 DTO 프로젝션이나 인터페이스 프로젝션 필요.
	 *           (예: List<SeatIdProjection> 인터페이스 사용)
	 */
	@Query("SELECT s.id FROM Seat s WHERE s.concert.id = :concertId")
	List<Long> findSeatIdsByConcertId(@Param("concertId") Long concertId);

	// 콘서트별 좌석 상태 조회
	List<Seat> findByConcertIdAndStatus(Long concertId, SeatStatus status);

	/**
	 * 콘서트별 전체 좌석 수.
	 *
	 * ■ count 메서드 (countBy...)
	 *   JPA가 SELECT COUNT(s.id) FROM Seat s WHERE s.concert_id = ? 쿼리를 생성.
	 *   SELECT 후 List 크기를 세는 것보다 훨씬 가볍다.
	 *   효과: DB에서 숫자만 반환 → 네트워크 전송 최소화.
	 *   단점: 없음. count 쿼리는 인덱스를 활용해 빠르게 실행됨.
	 */
	long countByConcertId(Long concertId);

	/** 콘서트별 예매 완료(RESERVED) 좌석 수. 예매 가능 수 = countByConcertId - countByConcertIdAndStatus */
	long countByConcertIdAndStatus(Long concertId, SeatStatus status);
}
