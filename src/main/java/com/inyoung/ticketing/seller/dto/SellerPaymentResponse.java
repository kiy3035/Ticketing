package com.inyoung.ticketing.seller.dto;

import java.time.OffsetDateTime;

public class SellerPaymentResponse {
	private String paymentKey;
	private String userId;
	private Long amount;
	private String status;
	private OffsetDateTime completedAt;

	public SellerPaymentResponse(String paymentKey, String userId, Long amount, String status, OffsetDateTime completedAt) {
		this.paymentKey = paymentKey;
		this.userId = userId;
		this.amount = amount;
		this.status = status;
		this.completedAt = completedAt;
	}

	public String getPaymentKey() { return paymentKey; }
	public String getUserId() { return userId; }
	public Long getAmount() { return amount; }
	public String getStatus() { return status; }
	public OffsetDateTime getCompletedAt() { return completedAt; }
}
