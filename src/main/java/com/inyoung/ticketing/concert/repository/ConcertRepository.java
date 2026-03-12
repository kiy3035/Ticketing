package com.inyoung.ticketing.concert.repository;

import java.time.Instant;
import java.util.List;
import com.inyoung.ticketing.concert.domain.Concert;
import com.inyoung.ticketing.concert.domain.ConcertCategory;
import com.inyoung.ticketing.concert.domain.ConcertStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 콘서트 기본 CRUD 리포지토리
public interface ConcertRepository extends JpaRepository<Concert, Long> {
	/** 판매자별 콘서트 목록 (판매자 대시보드용) */
	List<Concert> findBySeller_IdOrderByCreatedAtDesc(Long sellerId);

	/** 상태별 콘서트 목록 (취소된 공연 환불 배치용) */
	List<Concert> findByStatus(ConcertStatus status);

	// 특정 날짜에 공연이 있는 콘서트 개수
	long countByConcertAtBetween(Instant start, Instant end);

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

	/** 예매 가능 공연: 공연 일시가 현재 이후 */
	@Query("""
		select c from Concert c
		where (:category is null or c.category = :category)
		and (:query is null
			or lower(c.title) like lower(concat('%', :query, '%'))
			or lower(c.venue) like lower(concat('%', :query, '%')))
		and c.concertAt >= :now
		order by c.concertAt asc
	""")
	List<Concert> searchUpcomingConcerts(
		@Param("category") ConcertCategory category,
		@Param("query") String query,
		@Param("now") java.time.Instant now
	);

	/** 지난 공연: 공연 일시가 현재 이전 */
	@Query("""
		select c from Concert c
		where (:category is null or c.category = :category)
		and (:query is null
			or lower(c.title) like lower(concat('%', :query, '%'))
			or lower(c.venue) like lower(concat('%', :query, '%')))
		and c.concertAt < :now
		order by c.concertAt desc
	""")
	List<Concert> searchPastConcerts(
		@Param("category") ConcertCategory category,
		@Param("query") String query,
		@Param("now") java.time.Instant now
	);

	/** 마감된 공연(concertAt < now) 중 취소가 아닌 것. 미판매 좌석 통계용 */
	@Query("select c from Concert c where c.concertAt < :now and c.status <> :excludeStatus order by c.concertAt desc")
	Page<Concert> findEndedConcertsNotCancelled(
		@Param("now") Instant now,
		@Param("excludeStatus") ConcertStatus excludeStatus,
		Pageable pageable
	);

	/** 마감된 공연 중 취소 아닌 것 + 기간 필터(concertAt between from and to). from/to는 서비스에서 기본값 적용 후 호출 */
	@Query("""
		select c from Concert c
		where c.concertAt < :now and c.status <> :excludeStatus
		and c.concertAt >= :from and c.concertAt <= :to
		order by c.concertAt desc
		""")
	Page<Concert> findEndedConcertsNotCancelledBetween(
		@Param("now") Instant now,
		@Param("from") Instant from,
		@Param("to") Instant to,
		@Param("excludeStatus") ConcertStatus excludeStatus,
		Pageable pageable
	);
}
