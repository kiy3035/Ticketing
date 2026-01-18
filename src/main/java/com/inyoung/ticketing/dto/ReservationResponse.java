package com.inyoung.ticketing.dto;

import java.time.Instant;
import com.inyoung.ticketing.domain.Reservation;
import com.inyoung.ticketing.domain.ReservationStatus;

// 예약 확정 응답 DTO
public class ReservationResponse {
	private Long reservationId;
	private ReservationStatus status;
	private Instant reservedAt;

	// 엔티티에서 응답 DTO로 변환
	public ReservationResponse(Reservation reservation) {
		this.reservationId = reservation.getId();
		this.status = reservation.getStatus();
		this.reservedAt = reservation.getReservedAt();
	}

	// 예약 ID
	public Long getReservationId() {
		return reservationId;
	}

	// 예약 상태
	public ReservationStatus getStatus() {
		return status;
	}

	// 예약 시각
	public Instant getReservedAt() {
		return reservedAt;
	}
}
