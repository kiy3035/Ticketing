package com.inyoung.ticketing.reservation.dto;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import com.inyoung.ticketing.reservation.domain.Reservation;
import com.inyoung.ticketing.reservation.domain.ReservationStatus;

// 예약 확정 응답 DTO. reservedAt 은 DB(서울 LocalDateTime) → API용 OffsetDateTime(Asia/Seoul) 변환
public class ReservationResponse {
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private Long reservationId;
	private ReservationStatus status;
	private OffsetDateTime reservedAt;

	public ReservationResponse(Reservation reservation) {
		this.reservationId = reservation.getId();
		this.status = reservation.getStatus();
		java.time.LocalDateTime ldt = reservation.getReservedAt();
		this.reservedAt = ldt == null ? null : ldt.atZone(SEOUL).toOffsetDateTime();
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
	public OffsetDateTime getReservedAt() {
		return reservedAt;
	}
}
