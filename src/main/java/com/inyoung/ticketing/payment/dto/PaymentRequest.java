package com.inyoung.ticketing.payment.dto;

import com.inyoung.ticketing.payment.domain.PaymentMethod;
import jakarta.validation.constraints.NotBlank;

/**
 * 결제 요청 DTO. POST /api/payments/request body.
 * READY 상태의 Payment 를 생성하고, CARD 일 경우 orderId 를 부여해 프론트에 반환.
 */
public class PaymentRequest {
	@NotBlank
	private String holdToken;

	/** 결제 수단: POINT(기본, 포인트 차감) / CARD(토스 결제창 모의결제) */
	private PaymentMethod paymentMethod = PaymentMethod.POINT;

	public String getHoldToken() {
		return holdToken;
	}

	public void setHoldToken(String holdToken) {
		this.holdToken = holdToken;
	}

	public PaymentMethod getPaymentMethod() {
		return paymentMethod;
	}

	public void setPaymentMethod(PaymentMethod paymentMethod) {
		this.paymentMethod = paymentMethod;
	}
}
