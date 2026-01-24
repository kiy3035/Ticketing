package com.inyoung.ticketing.event;

import java.time.Instant;

// Kafka로 발행되는 홀드/예약 이벤트
public class SeatHoldEvent {
	private SeatHoldEventType type;
	private String holdToken;
	private Long concertId;
	private Long seatId;
	private String userId;
	private Instant expiresAt;
	private Instant occurredAt;

	public SeatHoldEvent() {
	}

	public SeatHoldEvent(
		SeatHoldEventType type,
		String holdToken,
		Long concertId,
		Long seatId,
		String userId,
		Instant expiresAt,
		Instant occurredAt
	) {
		this.type = type;
		this.holdToken = holdToken;
		this.concertId = concertId;
		this.seatId = seatId;
		this.userId = userId;
		this.expiresAt = expiresAt;
		this.occurredAt = occurredAt;
	}

	public SeatHoldEventType getType() {
		return type;
	}

	public String getHoldToken() {
		return holdToken;
	}

	public Long getConcertId() {
		return concertId;
	}

	public Long getSeatId() {
		return seatId;
	}

	public String getUserId() {
		return userId;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}
}
