package com.inyoung.ticketing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class HoldCreateRequest {
	@NotNull
	private Long concertId;

	@NotNull
	private Long seatId;

	@NotBlank
	private String userId;

	public Long getConcertId() {
		return concertId;
	}

	public void setConcertId(Long concertId) {
		this.concertId = concertId;
	}

	public Long getSeatId() {
		return seatId;
	}

	public void setSeatId(Long seatId) {
		this.seatId = seatId;
	}

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}
}
