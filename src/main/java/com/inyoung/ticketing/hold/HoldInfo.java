package com.inyoung.ticketing.hold;

import java.time.Instant;

// Redis에 저장되는 홀드 정보
public class HoldInfo {
	private String holdToken;
	private Long concertId;
	private Long seatId;
	private String userId;
	private Instant expiresAt;

	public HoldInfo() {
	}

	public HoldInfo(String holdToken, Long concertId, Long seatId, String userId, Instant expiresAt) {
		this.holdToken = holdToken;
		this.concertId = concertId;
		this.seatId = seatId;
		this.userId = userId;
		this.expiresAt = expiresAt;
	}

	public String getHoldToken() {
		return holdToken;
	}

	public void setHoldToken(String holdToken) {
		this.holdToken = holdToken;
	}

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

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public void setExpiresAt(Instant expiresAt) {
		this.expiresAt = expiresAt;
	}
}
