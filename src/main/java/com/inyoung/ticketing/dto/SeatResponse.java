package com.inyoung.ticketing.dto;

import com.inyoung.ticketing.domain.Seat;
import com.inyoung.ticketing.domain.SeatStatus;

public class SeatResponse {
	private Long id;
	private String section;
	private String seatNo;
	private Long price;
	private SeatStatus status;

	public SeatResponse(Seat seat) {
		this.id = seat.getId();
		this.section = seat.getSection();
		this.seatNo = seat.getSeatNo();
		this.price = seat.getPrice();
		this.status = seat.getStatus();
	}

	public Long getId() {
		return id;
	}

	public String getSection() {
		return section;
	}

	public String getSeatNo() {
		return seatNo;
	}

	public Long getPrice() {
		return price;
	}

	public SeatStatus getStatus() {
		return status;
	}
}
