package com.inyoung.ticketing.seat.dto;

import com.inyoung.ticketing.seat.domain.Seat;
import com.inyoung.ticketing.seat.domain.SeatStatus;

// 좌석 응답 DTO
public class SeatResponse {
	private Long id;
	private String section;
	private String seatNo;
	private Long price;
	private SeatStatus status;

	// 엔티티에서 응답 DTO로 변환
	public SeatResponse(Seat seat) {
		this.id = seat.getId();
		this.section = seat.getSection();
		this.seatNo = seat.getSeatNo();
		this.price = seat.getPrice();
		this.status = seat.getStatus();
	}

	public SeatResponse(Seat seat, SeatStatus status) {
		this.id = seat.getId();
		this.section = seat.getSection();
		this.seatNo = seat.getSeatNo();
		this.price = seat.getPrice();
		this.status = status;
	}

	// 좌석 ID
	public Long getId() {
		return id;
	}

	// 구역
	public String getSection() {
		return section;
	}

	// 좌석 번호
	public String getSeatNo() {
		return seatNo;
	}

	// 가격
	public Long getPrice() {
		return price;
	}

	// 좌석 상태
	public SeatStatus getStatus() {
		return status;
	}
}
