package com.inyoung.ticketing.domain;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

// 좌석 홀드(임시 점유) 엔티티
@Entity
@Table(
	name = "seat_hold",
	indexes = {
		@Index(name = "idx_seat_hold_token", columnList = "hold_token"),
		@Index(name = "idx_seat_hold_expires", columnList = "expires_at")
	}
)
public class SeatHold {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "concert_id", nullable = false)
	private Concert concert;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "seat_id", nullable = false)
	private Seat seat;

	@Column(nullable = false, length = 64)
	private String userId;

	@Column(name = "hold_token", nullable = false, length = 64, unique = true)
	private String holdToken;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	// 생성 시각 자동 설정
	@PrePersist
	void prePersist() {
		this.createdAt = Instant.now();
	}

	// 식별자
	public Long getId() {
		return id;
	}

	// 소속 콘서트
	public Concert getConcert() {
		return concert;
	}

	// 소속 콘서트 설정
	public void setConcert(Concert concert) {
		this.concert = concert;
	}

	// 대상 좌석
	public Seat getSeat() {
		return seat;
	}

	// 대상 좌석 설정
	public void setSeat(Seat seat) {
		this.seat = seat;
	}

	// 홀드 요청 사용자
	public String getUserId() {
		return userId;
	}

	// 홀드 요청 사용자 설정
	public void setUserId(String userId) {
		this.userId = userId;
	}

	// 홀드 토큰
	public String getHoldToken() {
		return holdToken;
	}

	// 홀드 토큰 설정
	public void setHoldToken(String holdToken) {
		this.holdToken = holdToken;
	}

	// 홀드 만료 시각
	public Instant getExpiresAt() {
		return expiresAt;
	}

	// 홀드 만료 시각 설정
	public void setExpiresAt(Instant expiresAt) {
		this.expiresAt = expiresAt;
	}

	// 생성 시각
	public Instant getCreatedAt() {
		return createdAt;
	}
}
