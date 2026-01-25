package com.inyoung.ticketing.repository;

import java.time.Instant;
import java.util.List;
import com.inyoung.ticketing.domain.Concert;
import com.inyoung.ticketing.domain.ConcertCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 콘서트 기본 CRUD 리포지토리
public interface ConcertRepository extends JpaRepository<Concert, Long> {
	// 특정 날짜에 시작하는 콘서트 개수
	long countByStartAtBetween(Instant start, Instant end);

	@Query("""
		select c from Concert c
		where (:category is null or c.category = :category)
		and (:query is null
			or lower(c.title) like lower(concat('%', :query, '%'))
			or lower(c.venue) like lower(concat('%', :query, '%')))
	""")
	List<Concert> searchConcerts(
		@Param("category") ConcertCategory category,
		@Param("query") String query
	);
}
