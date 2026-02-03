package com.inyoung.ticketing.payment.dto;

import jakarta.validation.constraints.NotBlank;

// 결제 요청 DTO (READY 생성)
public class PaymentRequest {
	@NotBlank
	private String holdToken;

	public String getHoldToken() {
		return holdToken;
	}

	public void setHoldToken(String holdToken) {
		this.holdToken = holdToken;
	}
}
