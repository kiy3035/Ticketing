package com.inyoung.ticketing.dto;

import jakarta.validation.constraints.NotBlank;

// 예약 확정 요청 DTO
public class ReservationRequest {
	@NotBlank
	private String holdToken;

	@NotBlank
	private String userId;

	// 홀드 토큰
	public String getHoldToken() {
		return holdToken;
	}

	// 홀드 토큰 설정
	public void setHoldToken(String holdToken) {
		this.holdToken = holdToken;
	}

	// 사용자 ID
	public String getUserId() {
		return userId;
	}

	// 사용자 ID 설정
	public void setUserId(String userId) {
		this.userId = userId;
	}
}
