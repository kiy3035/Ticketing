package com.inyoung.ticketing.repository;

import com.inyoung.ticketing.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
}
