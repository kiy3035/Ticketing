package com.inyoung.ticketing.admin.dto;

/**
 * 관리 인터페이스용 결제 응답 DTO.
 * paymentMethod: POINT(포인트 차감) / CARD(토스 결제) — 화면에서 금액 단위(포인트/원) 표시용.
 */
public class AdminPaymentResponse {
	private String paymentKey;
	private String username;
	private Long amount;
	private String paymentMethod;
	private String status;
	private String completedAt;

	public AdminPaymentResponse(String paymentKey, String username, Long amount, String paymentMethod, String status, String completedAt) {
		this.paymentKey = paymentKey;
		this.username = username;
		this.amount = amount;
		this.paymentMethod = paymentMethod != null ? paymentMethod : "POINT";
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

	public String getPaymentMethod() {
		return paymentMethod;
	}

	public String getStatus() {
		return status;
	}

	public String getCompletedAt() {
		return completedAt;
	}
}
