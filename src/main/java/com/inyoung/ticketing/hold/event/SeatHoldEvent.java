package com.inyoung.ticketing.hold.event;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * Kafka 로 발행되는 홀드/예약 이벤트.
 * <p>
 * {@link JsonAutoDetect}: setter 없이도 outbox 에서 JSON → 객체 역직렬화가 되게 한다(필드 직접 바인딩).
 * Kafka Consumer 쪽과 동일한 스키마를 유지해야 한다.
 * </p>
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
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
