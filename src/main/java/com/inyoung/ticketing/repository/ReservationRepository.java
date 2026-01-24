package com.inyoung.ticketing.repository;

import com.inyoung.ticketing.domain.Reservation;
import com.inyoung.ticketing.domain.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

// 예약 저장/조회 리포지토리
public interface ReservationRepository extends JpaRepository<Reservation, Long> {
	// 상태별 예약 개수
	long countByStatus(ReservationStatus status);
}
