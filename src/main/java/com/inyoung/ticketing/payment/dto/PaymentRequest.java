package com.inyoung.ticketing.payment.dto;

import com.inyoung.ticketing.payment.domain.PaymentMethod;
import jakarta.validation.constraints.NotBlank;

/**
 * 결제 요청 DTO. POST /api/payments/request body.
 * READY 상태의 Payment 를 생성하고, CARD 일 경우 orderId 를 부여해 프론트에 반환.
 * <p>
 * {@code compact constructor}(이름 없는 블록): JSON 에 {@code paymentMethod} 가 없으면 Jackson 이 null 을 넣고,
 * 여기서 {@link PaymentMethod#POINT} 로 바꿔 "기본값은 포인트" 규칙을 한 곳에 모은다.
 * </p>
 */
public record PaymentRequest(
	@NotBlank String holdToken,
	PaymentMethod paymentMethod
) {
	public PaymentRequest {
		if (paymentMethod == null) {
			paymentMethod = PaymentMethod.POINT;
		}
	}
}
