package com.inyoung.ticketing.dto;

import jakarta.validation.constraints.NotNull;

// 홀드 생성 요청 DTO
public class HoldCreateRequest {
	@NotNull
	private Long concertId;

	@NotNull
	private Long seatId;

	// 콘서트 ID
	public Long getConcertId() {
		return concertId;
	}

	// 콘서트 ID 설정
	public void setConcertId(Long concertId) {
		this.concertId = concertId;
	}

	// 좌석 ID
	public Long getSeatId() {
		return seatId;
	}

	// 좌석 ID 설정
	public void setSeatId(Long seatId) {
		this.seatId = seatId;
	}
}
