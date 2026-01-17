package com.inyoung.ticketing.dto;

import java.time.Instant;
import com.inyoung.ticketing.domain.Reservation;
import com.inyoung.ticketing.domain.ReservationStatus;

public class ReservationResponse {
	private Long reservationId;
	private ReservationStatus status;
	private Instant reservedAt;

	public ReservationResponse(Reservation reservation) {
		this.reservationId = reservation.getId();
		this.status = reservation.getStatus();
		this.reservedAt = reservation.getReservedAt();
	}

	public Long getReservationId() {
		return reservationId;
	}

	public ReservationStatus getStatus() {
		return status;
	}

	public Instant getReservedAt() {
		return reservedAt;
	}
}
