package com.inyoung.ticketing.event;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonFormat;

// Kafka로 발행되는 홀드/예약 이벤트
public class SeatHoldEvent {
	private SeatHoldEventType type;
	private String holdToken;
	private Long concertId;
	private Long seatId;
	private String userId;
	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "Asia/Seoul")
	private Instant expiresAt;
	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "Asia/Seoul")
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
