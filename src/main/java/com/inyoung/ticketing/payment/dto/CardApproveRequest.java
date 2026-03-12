package com.inyoung.ticketing.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 카드 결제 시, 토스 결제창 인증 완료 후 successUrl 리다이렉트로 쿼리 전달되는 값을
 * 백엔드 승인 API(POST /api/payments/{paymentKey}/approve) body 로 넘길 때 사용하는 DTO.
 * <p>
 * 프론트: successUrl 에서 paymentKey, orderId, amount 를 받아 이 DTO 로 우리 서버 approve 호출.
 * 백엔드: 이 값으로 토스 결제 승인 API(POST /v1/payments/confirm) 호출 후 payment 를 APPROVED 처리.
 */
public class CardApproveRequest {
	/** 토스에서 successUrl 리다이렉트 시 쿼리로 전달한 paymentKey (토스 결제 식별자) */
	@NotBlank
	private String paymentKey;
	/** 결제 요청 시 우리가 부여한 주문 ID (payment.orderId 와 일치해야 함) */
	@NotBlank
	private String orderId;
	/** 결제 금액(원). payment.amount 와 일치해야 함 */
	@NotNull
	@Positive
	private Long amount;

	public String getPaymentKey() {
		return paymentKey;
	}

	public void setPaymentKey(String paymentKey) {
		this.paymentKey = paymentKey;
	}

	public String getOrderId() {
		return orderId;
	}

	public void setOrderId(String orderId) {
		this.orderId = orderId;
	}

	public Long getAmount() {
		return amount;
	}

	public void setAmount(Long amount) {
		this.amount = amount;
	}
}
