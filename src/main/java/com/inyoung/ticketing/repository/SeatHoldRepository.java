package com.inyoung.ticketing.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import com.inyoung.ticketing.domain.SeatHold;
import org.springframework.data.jpa.repository.JpaRepository;

// 홀드 조회/저장 리포지토리
public interface SeatHoldRepository extends JpaRepository<SeatHold, Long> {
	// 홀드 토큰으로 조회
	Optional<SeatHold> findByHoldToken(String holdToken);

	// 만료 시각 기준으로 만료된 홀드 조회
	List<SeatHold> findByExpiresAtBefore(Instant time);
}
