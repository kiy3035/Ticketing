package com.inyoung.ticketing.dto;

import jakarta.validation.constraints.NotBlank;

public class ReservationRequest {
	@NotBlank
	private String holdToken;

	@NotBlank
	private String userId;

	public String getHoldToken() {
		return holdToken;
	}

	public void setHoldToken(String holdToken) {
		this.holdToken = holdToken;
	}

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}
}
