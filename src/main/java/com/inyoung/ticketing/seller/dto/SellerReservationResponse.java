package com.inyoung.ticketing.seller.dto;

import com.inyoung.ticketing.reservation.domain.ReservationStatus;
import java.time.OffsetDateTime;

public class SellerReservationResponse {
	private Long id;
	private String userId;
	private String section;
	private String seatNo;
	private Long price;
	private ReservationStatus status;
	private OffsetDateTime reservedAt;

	public SellerReservationResponse(Long id, String userId, String section, String seatNo, Long price,
		ReservationStatus status, OffsetDateTime reservedAt) {
		this.id = id;
		this.userId = userId;
		this.section = section;
		this.seatNo = seatNo;
		this.price = price;
		this.status = status;
		this.reservedAt = reservedAt;
	}

	public Long getId() { return id; }
	public String getUserId() { return userId; }
	public String getSection() { return section; }
	public String getSeatNo() { return seatNo; }
	public Long getPrice() { return price; }
	public ReservationStatus getStatus() { return status; }
	public OffsetDateTime getReservedAt() { return reservedAt; }
}
