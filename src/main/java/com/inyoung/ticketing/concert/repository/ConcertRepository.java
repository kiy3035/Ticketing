package com.inyoung.ticketing.concert.repository;

import java.time.Instant;
import java.util.List;
import com.inyoung.ticketing.concert.domain.Concert;
import com.inyoung.ticketing.concert.domain.ConcertCategory;
import com.inyoung.ticketing.concert.domain.ConcertStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 콘서트 기본 CRUD 리포지토리
public interface ConcertRepository extends JpaRepository<Concert, Long> {
	/** 판매자별 콘서트 목록 (판매자 대시보드용) */
	List<Concert> findBySeller_IdOrderByCreatedAtDesc(Long sellerId);

	/** 상태별 콘서트 목록 (취소된 공연 환불 배치용) */
	List<Concert> findByStatus(ConcertStatus status);

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

	/** 예매 가능 공연: 종료 시각이 현재 이후. 오늘 날짜·현재 시간 기준 */
	@Query("""
		select c from Concert c
		where (:category is null or c.category = :category)
		and (:query is null
			or lower(c.title) like lower(concat('%', :query, '%'))
			or lower(c.venue) like lower(concat('%', :query, '%')))
		and c.endAt >= :now
		order by c.startAt asc
	""")
	List<Concert> searchUpcomingConcerts(
		@Param("category") ConcertCategory category,
		@Param("query") String query,
		@Param("now") java.time.Instant now
	);

	/** 지난 공연: 종료 시각이 현재 이전. 오늘 날짜·현재 시간 기준 */
	@Query("""
		select c from Concert c
		where (:category is null or c.category = :category)
		and (:query is null
			or lower(c.title) like lower(concat('%', :query, '%'))
			or lower(c.venue) like lower(concat('%', :query, '%')))
		and c.endAt < :now
		order by c.endAt desc
	""")
	List<Concert> searchPastConcerts(
		@Param("category") ConcertCategory category,
		@Param("query") String query,
		@Param("now") java.time.Instant now
	);
}
