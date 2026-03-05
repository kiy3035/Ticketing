package com.inyoung.ticketing.reservation.repository;

import com.inyoung.ticketing.reservation.domain.Reservation;
import com.inyoung.ticketing.reservation.domain.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

// 예약 저장/조회 리포지토리
public interface ReservationRepository extends JpaRepository<Reservation, Long> {
	// 상태별 예약 개수
	long countByStatus(ReservationStatus status);

	// 사용자별 예약 내역 최신순 조회
	java.util.List<Reservation> findByUserIdOrderByReservedAtDesc(String userId);

	/** 콘서트별 예약 목록 최신순 (판매자 대시보드용) */
	java.util.List<Reservation> findByConcert_IdOrderByReservedAtDesc(Long concertId);
}
