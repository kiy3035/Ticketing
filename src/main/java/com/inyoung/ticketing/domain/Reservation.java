package com.inyoung.ticketing.domain;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

// 좌석 예약 확정 엔티티
@Entity
@Table(name = "reservation")
public class Reservation {
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

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReservationStatus status = ReservationStatus.CONFIRMED;

	@Column(nullable = false)
	private Instant reservedAt;

	// 예약 시각 자동 설정
	@PrePersist
	void prePersist() {
		this.reservedAt = Instant.now();
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

	// 예약 좌석
	public Seat getSeat() {
		return seat;
	}

	// 예약 좌석 설정
	public void setSeat(Seat seat) {
		this.seat = seat;
	}

	// 예약 사용자
	public String getUserId() {
		return userId;
	}

	// 예약 사용자 설정
	public void setUserId(String userId) {
		this.userId = userId;
	}

	// 예약 상태
	public ReservationStatus getStatus() {
		return status;
	}

	// 예약 상태 설정
	public void setStatus(ReservationStatus status) {
		this.status = status;
	}

	// 예약 시각
	public Instant getReservedAt() {
		return reservedAt;
	}
}
