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

	@PrePersist
	void prePersist() {
		this.createdAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public Concert getConcert() {
		return concert;
	}

	public void setConcert(Concert concert) {
		this.concert = concert;
	}

	public Seat getSeat() {
		return seat;
	}

	public void setSeat(Seat seat) {
		this.seat = seat;
	}

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	public String getHoldToken() {
		return holdToken;
	}

	public void setHoldToken(String holdToken) {
		this.holdToken = holdToken;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public void setExpiresAt(Instant expiresAt) {
		this.expiresAt = expiresAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
