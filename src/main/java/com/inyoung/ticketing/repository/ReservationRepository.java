package com.inyoung.ticketing.repository;

import com.inyoung.ticketing.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

// 예약 저장/조회 리포지토리
public interface ReservationRepository extends JpaRepository<Reservation, Long> {
}
