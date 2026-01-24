package com.inyoung.ticketing.repository;

import java.time.Instant;
import com.inyoung.ticketing.domain.Concert;
import org.springframework.data.jpa.repository.JpaRepository;

// 콘서트 기본 CRUD 리포지토리
public interface ConcertRepository extends JpaRepository<Concert, Long> {
	// 특정 날짜에 시작하는 콘서트 개수
	long countByStartAtBetween(Instant start, Instant end);
}
