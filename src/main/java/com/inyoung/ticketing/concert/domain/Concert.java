package com.inyoung.ticketing.concert.domain;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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

	@Column(nullable = false)
	private Instant startAt;

	@Column(nullable = false)
	private Instant endAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ConcertStatus status = ConcertStatus.UPCOMING;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ConcertCategory category;

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

	// 시작 시각
	public Instant getStartAt() {
		return startAt;
	}

	// 시작 시각 설정
	public void setStartAt(Instant startAt) {
		this.startAt = startAt;
	}

	// 종료 시각
	public Instant getEndAt() {
		return endAt;
	}

	// 종료 시각 설정
	public void setEndAt(Instant endAt) {
		this.endAt = endAt;
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

	// 생성 시각
	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}
}
