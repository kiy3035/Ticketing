package com.inyoung.ticketing.hold.dto;

import java.time.Instant;

// 홀드 응답 DTO
public class HoldResponse {
	private String holdToken;
	private Instant expiresAt;

	public HoldResponse(String holdToken, Instant expiresAt) {
		this.holdToken = holdToken;
		this.expiresAt = expiresAt;
	}

	// 홀드 토큰
	public String getHoldToken() {
		return holdToken;
	}

	// 만료 시각
	public Instant getExpiresAt() {
		return expiresAt;
	}
}
