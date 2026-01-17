package com.inyoung.ticketing.dto;

import java.time.Instant;
import com.inyoung.ticketing.domain.SeatHold;

public class HoldResponse {
	private Long holdId;
	private String holdToken;
	private Instant expiresAt;

	public HoldResponse(SeatHold hold) {
		this.holdId = hold.getId();
		this.holdToken = hold.getHoldToken();
		this.expiresAt = hold.getExpiresAt();
	}

	public Long getHoldId() {
		return holdId;
	}

	public String getHoldToken() {
		return holdToken;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}
}
