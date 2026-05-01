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

/**
 * ════════════════════════════════════════════════════════════════
 * [ConcertRepository]
 *
 * ■ JpaRepository<Concert, Long> 상속
 *   Spring Data JPA가 런타임에 프록시 구현체를 자동 생성한다.
 *   개발자는 인터페이스 선언만 하면 아래 기능을 무료로 얻는다:
 *   - save(), findById(), findAll(), delete(), count(), existsById() 등 기본 CRUD
 *   - Pageable 기반 페이징/정렬 (findAll(Pageable))
 *   - 배치 저장 (saveAll())
 *   효과: JDBC/MyBatis처럼 SQL을 직접 작성하지 않아도 기본 CRUD 완성.
 *   단점: 복잡한 동적 쿼리(조건이 런타임에 결정)는 Querydsl 또는 @Query가 필요.
 *         현재 프로젝트는 @Query로 처리. 조건이 더 복잡해지면 Querydsl 도입 고려.
 *
 * ■ 메서드 이름으로 쿼리 생성 (Query Method)
 *   Spring Data JPA는 메서드 이름을 파싱해 자동으로 JPQL을 만든다.
 *   규칙: find[Entity]By[조건]
 *   예: findBySeller_IdOrderByCreatedAtDesc
 *       → WHERE seller.id = ? ORDER BY createdAt DESC
 *   언더스코어(_)는 연관 엔티티 탐색 구분자:
 *       Seller_Id → seller 필드(Users)의 id 필드
 *   효과: 단순 조건 쿼리를 코드 없이 선언적으로 작성.
 *   단점: 조건이 많아질수록 메서드명이 매우 길어져 가독성 저하.
 *         (예: findByStatusAndConcertAtAfterAndCategoryOrderByCreatedAtDesc)
 *         이 경우 @Query를 쓰는 것이 낫다.
 * ════════════════════════════════════════════════════════════════
 */
public interface ConcertRepository extends JpaRepository<Concert, Long> {

	/** 판매자별 콘서트 목록 (판매자 대시보드용) */
	List<Concert> findBySeller_IdOrderByCreatedAtDesc(Long sellerId);

	/** 상태별 콘서트 목록 (취소된 공연 환불 배치용) */
	List<Concert> findByStatus(ConcertStatus status);

	// 특정 날짜에 공연이 있는 콘서트 개수
	long countByConcertAtBetween(Instant start, Instant end);

	/**
	 * 카테고리·키워드 동적 검색 (@Query JPQL).
	 *
	 * ■ @Query 사용 이유
	 *   메서드명으로 표현하기 어려운 복합 조건 (category가 null이면 전체, query가 null이면 전체)을
	 *   하나의 쿼리로 처리하기 위해 @Query를 사용했다.
	 *
	 * ■ :category is null or c.category = :category 패턴
	 *   - category 파라미터가 null이면 조건 자체를 무시 (전체 조회).
	 *   - null이 아니면 해당 카테고리로 필터링.
	 *   - 효과: 조건 유무에 따라 별도 메서드를 만들 필요 없이 하나의 쿼리로 처리.
	 *   - 단점: MySQL 옵티마이저가 IS NULL 체크 때문에 인덱스를 비효율적으로 쓸 수 있다.
	 *           데이터가 매우 많아지면 Querydsl로 조건을 동적으로 조립하는 방식이 더 낫다.
	 *
	 * ■ lower(c.title) like lower(concat('%', :query, '%'))
	 *   - 대소문자 구분 없이 제목/공연장에서 키워드 포함 여부 검색.
	 *   - '%키워드%' 패턴: 양쪽에 와일드카드 → 인덱스를 타지 않는다 (full scan).
	 *   - 단점: 데이터가 많아지면 느려진다. 검색 성능이 중요해지면
	 *           MySQL Full-Text Index 또는 Elasticsearch 도입 필요.
	 */
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

	@Query("select count(c) from Concert c where c.concertAt >= :now")
	long countUpcomingConcerts(@Param("now") Instant now);

	@Query("select count(c) from Concert c where c.concertAt < :now")
	long countPastConcerts(@Param("now") Instant now);

	/**
	 * 마감된 공연(concertAt < now) 중 취소가 아닌 것.
	 * 미판매 좌석 통계용.
	 *
	 * ■ Page<Concert> + Pageable
	 *   - Page: 데이터 목록 + 전체 건수 + 페이지 정보를 함께 반환하는 Spring Data 객체.
	 *   - Pageable: 페이지 번호, 페이지 크기, 정렬 정보를 담은 파라미터.
	 *   - 효과: 대용량 데이터를 한 번에 가져오지 않고 페이지 단위로 분할 조회.
	 *           전체 건수(count 쿼리)도 자동 실행돼 클라이언트에 totalElements 제공.
	 *   - 단점: count 쿼리가 추가로 실행된다 (성능 민감 시 countQuery를 별도 지정 가능).
	 *           예: @Query(value="...", countQuery="select count(c) from Concert c where ...")
	 */
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
