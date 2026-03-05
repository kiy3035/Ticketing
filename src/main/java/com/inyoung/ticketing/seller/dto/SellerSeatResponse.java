package com.inyoung.ticketing.seller.dto;

import com.inyoung.ticketing.seat.domain.SeatStatus;

public class SellerSeatResponse {
	private Long id;
	private String section;
	private String seatNo;
	private Long price;
	private SeatStatus status;

	public SellerSeatResponse(Long id, String section, String seatNo, Long price, SeatStatus status) {
		this.id = id;
		this.section = section;
		this.seatNo = seatNo;
		this.price = price;
		this.status = status;
	}

	public Long getId() { return id; }
	public String getSection() { return section; }
	public String getSeatNo() { return seatNo; }
	public Long getPrice() { return price; }
	public SeatStatus getStatus() { return status; }
}
