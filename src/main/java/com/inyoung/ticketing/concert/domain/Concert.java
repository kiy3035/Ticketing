package com.inyoung.ticketing.concert.domain;

import com.inyoung.ticketing.auth.domain.Users;
import com.inyoung.ticketing.common.domain.BaseEntity;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * ════════════════════════════════════════════════════════════════
 * [Concert 엔티티]
 *
 * ■ @Entity
 *   - JPA가 이 클래스를 "영속성 컨텍스트(Persistence Context)"로 관리하겠다는 선언.
 *   - 영속성 컨텍스트: JPA가 DB와 애플리케이션 사이에서 엔티티를 캐싱·추적하는 1차 캐시.
 *     같은 트랜잭션 내에서 동일 ID로 조회하면 DB를 다시 치지 않고 캐시에서 반환.
 *   - 반드시 기본 생성자(public 또는 protected)가 있어야 한다.
 *     이유: JPA가 리플렉션으로 객체를 생성할 때 기본 생성자를 사용하기 때문.
 *   - 단점: 엔티티 클래스에 비즈니스 로직을 섞으면 "빈혈 도메인 모델" 문제가 생긴다.
 *           현재 이 프로젝트는 getter/setter 위주라 서비스 레이어에 로직이 몰려 있음.
 *
 * ■ @Table(name = "concert")
 *   - 클래스명이 "Concert"이면 기본적으로 테이블명도 "concert"가 되지만,
 *     명시적으로 써주는 게 좋다: 나중에 클래스 이름을 바꿔도 테이블명은 그대로 유지됨.
 *
 * ■ @Id + @GeneratedValue(strategy = GenerationType.IDENTITY)
 *   - IDENTITY 전략: DB의 AUTO_INCREMENT를 사용해 PK를 생성.
 *   - 효과: 별도의 시퀀스 테이블 없이 간단하게 PK 자동 생성.
 *   - 단점: INSERT 직후 DB가 생성한 ID를 가져와야 하므로,
 *           배치 INSERT(여러 행을 한 번에 INSERT) 시 성능이 나빠진다.
 *           (SEQUENCE 전략은 ID를 미리 알 수 있어 배치 가능)
 *           → 이 프로젝트에서는 배치 INSERT가 없으므로 IDENTITY로 충분.
 * ════════════════════════════════════════════════════════════════
 */
@Entity
@Table(name = "concert")
public class Concert extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * @Column(nullable = false, length = 200)
	 *   - nullable = false: INSERT/UPDATE 시 JPA 레벨에서 null 체크 (Bean Validation @NotNull과는 별개).
	 *     JPA의 ddl-auto가 create/update일 때는 DDL에 NOT NULL도 추가됨.
	 *     이 프로젝트는 Flyway로 DDL을 관리하므로 ddl-auto=validate — 실제 DDL은 Flyway가 만들고,
	 *     @Column 설정은 스키마 문서화 역할 + 런타임 검증 역할을 한다.
	 *   - length = 200: VARCHAR(200) 힌트. Flyway 마이그레이션 파일과 일치시켜야 한다.
	 *   - 단점: JPA 레벨 검증은 flush 시점(실제 SQL 실행 시점)에 발생하므로
	 *           Controller 레이어에서 Bean Validation(@Valid)을 별도로 써야 사용자에게 즉시 오류 전달 가능.
	 */
	@Column(nullable = false, length = 200)
	private String title;

	@Column(nullable = false, length = 200)
	private String venue;

	/**
	 * 공연 일시.
	 * Instant: 타임존 없는 UTC 기준 에포크 시각. DB에는 DATETIME으로 저장 (서울 시간 기준).
	 * → 서버 JVM 타임존을 Asia/Seoul로 설정했기 때문에 Instant ↔ LocalDateTime 변환이 자동.
	 * 단점: 배포 환경 JVM 타임존이 달라지면 시각이 틀어진다.
	 *       UTC로 통일하려면 TIMESTAMP 컬럼을 쓰는 것이 더 안전.
	 */
	@Column(nullable = true, columnDefinition = "DATETIME")
	private Instant concertAt;

	/**
	 * @Enumerated(EnumType.STRING)
	 *   - enum 값을 DB에 문자열(예: "UPCOMING", "ENDED")로 저장.
	 *   - 왜 STRING인가?
	 *     기본값인 ORDINAL(숫자 0, 1, 2...)을 쓰면 enum에 값을 추가하거나 순서를 바꿀 때
	 *     기존 DB 데이터가 전혀 다른 의미로 읽히는 치명적 버그가 생긴다.
	 *     예: UPCOMING(0), ENDED(1) → UPCOMING(0), CANCELLED(1), ENDED(2) 로 바꾸면
	 *         기존 ENDED 레코드가 CANCELLED로 둔갑함.
	 *   - 효과: 코드와 DB 모두 가독성 좋고, enum 순서 변경에 안전.
	 *   - 단점: 문자열 저장이라 숫자보다 스토리지를 조금 더 쓴다 (실무에서는 무시할 수준).
	 */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ConcertStatus status = ConcertStatus.UPCOMING;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ConcertCategory category;

	/**
	 * 판매자(Users) 연관관계.
	 *
	 * ■ @ManyToOne(fetch = FetchType.LAZY)
	 *   - 다대일 관계: 여러 Concert가 한 Users(판매자)에 속함.
	 *   - 기본값은 FetchType.EAGER — Concert 조회 시 Users까지 자동 JOIN해서 가져옴.
	 *   - 여기서 LAZY로 바꾼 이유:
	 *     콘서트 목록 조회 API에서 판매자 정보가 필요 없는 경우가 많다.
	 *     EAGER면 N개의 콘서트를 조회할 때 N번의 Users 쿼리가 추가 발생 (N+1 문제).
	 *   - LAZY의 동작 방식:
	 *     Concert 객체의 seller 필드는 처음엔 "프록시 객체"로 채워진다.
	 *     getSeller().getUsername() 처럼 실제 필드에 접근하는 순간 SELECT 쿼리가 실행됨.
	 *   - 효과: 불필요한 JOIN 없이 콘서트만 조회 → 성능 향상.
	 *   - 단점1: 트랜잭션이 끝난 뒤 seller에 접근하면 LazyInitializationException 발생.
	 *            (영속성 컨텍스트가 닫혀 프록시를 초기화할 수 없기 때문)
	 *            → 서비스 레이어에서 트랜잭션 내에 seller를 미리 접근하거나,
	 *               DTO로 변환해서 컨트롤러에 넘기면 해결됨.
	 *   - 단점2: seller가 필요한 쿼리에서 LAZY면 쿼리 2번 (콘서트 1번 + 판매자 1번).
	 *            JPQL에서 JOIN FETCH c.seller 하면 한 번에 해결 가능.
	 *
	 * ■ @JoinColumn(name = "seller_id")
	 *   - Concert 테이블에 seller_id 컬럼을 FK로 만든다는 선언.
	 *   - 명시하지 않으면 JPA가 자동으로 컬럼명을 만들지만, 명시하는 게 명확함.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "seller_id")
	private Users seller;

	// 식별자
	public Long getId() {
		return id;
	}

	// 콘서트 제목
	public String getTitle() {
		return title;
	}

	// 콘서트 제목 설정
	public void setTitle(String title) {
		this.title = title;
	}

	// 공연장
	public String getVenue() {
		return venue;
	}

	// 공연장 설정
	public void setVenue(String venue) {
		this.venue = venue;
	}

	// 공연 일시
	public Instant getConcertAt() {
		return concertAt;
	}

	// 공연 일시 설정
	public void setConcertAt(Instant concertAt) {
		this.concertAt = concertAt;
	}

	// 진행 상태
	public ConcertStatus getStatus() {
		return status;
	}

	// 진행 상태 설정
	public void setStatus(ConcertStatus status) {
		this.status = status;
	}

	// 카테고리
	public ConcertCategory getCategory() {
		return category;
	}

	// 카테고리 설정
	public void setCategory(ConcertCategory category) {
		this.category = category;
	}

	// 판매자 (null이면 시스템/관리자 등록)
	public Users getSeller() {
		return seller;
	}

	public void setSeller(Users seller) {
		this.seller = seller;
	}
}
