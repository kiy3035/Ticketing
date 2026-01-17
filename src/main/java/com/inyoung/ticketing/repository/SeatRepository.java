package com.inyoung.ticketing.repository;

import java.util.List;
import com.inyoung.ticketing.domain.Seat;
import com.inyoung.ticketing.domain.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {
	List<Seat> findByConcertId(Long concertId);

	List<Seat> findByConcertIdAndStatus(Long concertId, SeatStatus status);
}
