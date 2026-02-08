package com.inyoung.ticketing.admin.dto;

/**
 * 관리 인터페이스용 결제 응답 DTO
 */
public class AdminPaymentResponse {
	private String paymentKey;
	private String username;
	private Long amount;
	private String status;
	private String completedAt;

	public AdminPaymentResponse(String paymentKey, String username, Long amount, String status, String completedAt) {
		this.paymentKey = paymentKey;
		this.username = username;
		this.amount = amount;
		this.status = status;
		this.completedAt = completedAt;
	}

	public String getPaymentKey() {
		return paymentKey;
	}

	public String getUsername() {
		return username;
	}

	public Long getAmount() {
		return amount;
	}

	public String getStatus() {
		return status;
	}

	public String getCompletedAt() {
		return completedAt;
	}
}
