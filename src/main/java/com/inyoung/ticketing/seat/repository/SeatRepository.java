package com.inyoung.ticketing.seat.repository;

import java.util.List;
import com.inyoung.ticketing.seat.domain.Seat;
import com.inyoung.ticketing.seat.domain.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 좌석 조회/저장 리포지토리
public interface SeatRepository extends JpaRepository<Seat, Long> {
	// 콘서트별 좌석 목록 조회
	List<Seat> findByConcertId(Long concertId);

	/** 콘서트별 좌석 ID 목록 (대기열 가용 좌석 수 계산 시 홀드 수 반영용) */
	@Query("SELECT s.id FROM Seat s WHERE s.concert.id = :concertId")
	List<Long> findSeatIdsByConcertId(@Param("concertId") Long concertId);

	// 콘서트별 좌석 상태 조회
	List<Seat> findByConcertIdAndStatus(Long concertId, SeatStatus status);

	/** 콘서트별 전체 좌석 수 (대기열 입장 제한 계산용) */
	long countByConcertId(Long concertId);

	/** 콘서트별 예매 완료(RESERVED) 좌석 수. 예매 가능 수 = countByConcertId - countByConcertIdAndStatus */
	long countByConcertIdAndStatus(Long concertId, SeatStatus status);
}
