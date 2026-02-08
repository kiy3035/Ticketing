package com.inyoung.ticketing.payment.event;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonProperty;

// 결제 완료 이벤트
public class PaymentCompleteEvent {
	@JsonProperty("payment_key")
	private String paymentKey;

	@JsonProperty("user_id")
	private String userId;

	@JsonProperty("concert_id")
	private Long concertId;

	@JsonProperty("amount")
	private Long amount;

	@JsonProperty("timestamp")
	private Instant timestamp;

	public PaymentCompleteEvent() {}

	public PaymentCompleteEvent(String paymentKey, String userId, Long concertId, Long amount) {
		this.paymentKey = paymentKey;
		this.userId = userId;
		this.concertId = concertId;
		this.amount = amount;
		this.timestamp = Instant.now();
	}

	public String getPaymentKey() {
		return paymentKey;
	}

	public void setPaymentKey(String paymentKey) {
		this.paymentKey = paymentKey;
	}

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	public Long getConcertId() {
		return concertId;
	}

	public void setConcertId(Long concertId) {
		this.concertId = concertId;
	}

	public Long getAmount() {
		return amount;
	}

	public void setAmount(Long amount) {
		this.amount = amount;
	}

	public Instant getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(Instant timestamp) {
		this.timestamp = timestamp;
	}
}
