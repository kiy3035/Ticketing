package com.inyoung.ticketing.concert.domain;

import com.inyoung.ticketing.auth.domain.Users;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

// 콘서트 기본 정보 엔티티
@Entity
@Table(name = "concert")
public class Concert {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(nullable = false, length = 200)
	private String venue;

	/** 공연 일시 (한국 시간 기준). DB에는 DATETIME으로 저장해 로컬·앱·DB 표기가 동일하게 맞춤 */
	@Column(nullable = true, columnDefinition = "DATETIME")
	private Instant concertAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ConcertStatus status = ConcertStatus.UPCOMING;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ConcertCategory category;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "seller_id")
	private Users seller;

	@Column(nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	// 생성 시각 자동 설정 (한국시간, 초단위까지만)
	@PrePersist
	void prePersist() {
		this.createdAt = OffsetDateTime.now(ZoneId.of("Asia/Seoul")).withNano(0);
	}

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

	// 생성 시각
	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}
}
