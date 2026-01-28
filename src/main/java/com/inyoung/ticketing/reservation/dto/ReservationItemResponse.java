package com.inyoung.ticketing.reservation.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import com.inyoung.ticketing.reservation.domain.ReservationStatus;

// 예매내역 목록 응답 DTO
public class ReservationItemResponse {
	private final Long reservationId;
	private final String concertTitle;
	private final String venue;
	private final Instant startAt;
	private final Instant endAt;
	private final String seatSection;
	private final String seatNo;
	private final Long seatPrice;
	private final ReservationStatus status;
	private final OffsetDateTime reservedAt;

	public ReservationItemResponse(
		Long reservationId,
		String concertTitle,
		String venue,
		Instant startAt,
		Instant endAt,
		String seatSection,
		String seatNo,
		Long seatPrice,
		ReservationStatus status,
		OffsetDateTime reservedAt
	) {
		this.reservationId = reservationId;
		this.concertTitle = concertTitle;
		this.venue = venue;
		this.startAt = startAt;
		this.endAt = endAt;
		this.seatSection = seatSection;
		this.seatNo = seatNo;
		this.seatPrice = seatPrice;
		this.status = status;
		this.reservedAt = reservedAt;
	}

	public Long getReservationId() {
		return reservationId;
	}

	public String getConcertTitle() {
		return concertTitle;
	}

	public String getVenue() {
		return venue;
	}

	public Instant getStartAt() {
		return startAt;
	}

	public Instant getEndAt() {
		return endAt;
	}

	public String getSeatSection() {
		return seatSection;
	}

	public String getSeatNo() {
		return seatNo;
	}

	public Long getSeatPrice() {
		return seatPrice;
	}

	public ReservationStatus getStatus() {
		return status;
	}

	public OffsetDateTime getReservedAt() {
		return reservedAt;
	}
}
