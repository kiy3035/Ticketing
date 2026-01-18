package com.inyoung.ticketing.repository;

import java.util.List;
import com.inyoung.ticketing.domain.Seat;
import com.inyoung.ticketing.domain.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;

// 좌석 조회/저장 리포지토리
public interface SeatRepository extends JpaRepository<Seat, Long> {
	// 콘서트별 좌석 목록 조회
	List<Seat> findByConcertId(Long concertId);

	// 콘서트별 좌석 상태 조회
	List<Seat> findByConcertIdAndStatus(Long concertId, SeatStatus status);
}
