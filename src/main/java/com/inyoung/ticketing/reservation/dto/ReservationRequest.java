package com.inyoung.ticketing.reservation.dto;

import jakarta.validation.constraints.NotBlank;

// 예약 확정 요청 DTO
public class ReservationRequest {
	@NotBlank
	private String holdToken;

	// 홀드 토큰
	public String getHoldToken() {
		return holdToken;
	}

	// 홀드 토큰 설정
	public void setHoldToken(String holdToken) {
		this.holdToken = holdToken;
	}
}
