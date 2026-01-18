package com.inyoung.ticketing.dto;

import java.time.Instant;
import com.inyoung.ticketing.domain.SeatHold;

// 홀드 응답 DTO
public class HoldResponse {
	private Long holdId;
	private String holdToken;
	private Instant expiresAt;

	// 엔티티에서 응답 DTO로 변환
	public HoldResponse(SeatHold hold) {
		this.holdId = hold.getId();
		this.holdToken = hold.getHoldToken();
		this.expiresAt = hold.getExpiresAt();
	}

	// 홀드 ID
	public Long getHoldId() {
		return holdId;
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
