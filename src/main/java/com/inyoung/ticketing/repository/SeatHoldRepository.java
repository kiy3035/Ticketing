package com.inyoung.ticketing.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import com.inyoung.ticketing.domain.SeatHold;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatHoldRepository extends JpaRepository<SeatHold, Long> {
	Optional<SeatHold> findByHoldToken(String holdToken);

	List<SeatHold> findByExpiresAtBefore(Instant time);
}
