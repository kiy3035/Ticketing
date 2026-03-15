package com.inyoung.ticketing.payment.dto;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import com.inyoung.ticketing.payment.domain.Payment;
import com.inyoung.ticketing.payment.domain.PaymentMethod;
import com.inyoung.ticketing.payment.domain.PaymentStatus;

/**
 * 결제 API 응답 DTO.
 * paymentMethod, orderId 는 카드 결제 시 프론트에서 토스 결제창 요청에 사용.
 * 시각 필드는 DB 저장값(서울 LocalDateTime)을 API 응답용으로 OffsetDateTime(Asia/Seoul) 로 변환해 반환.
 */
public class PaymentResponse {
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final String paymentKey;
	private final PaymentMethod paymentMethod;
	private final String orderId;
	private final PaymentStatus status;
	private final Long amount;
	private final Long reservationId;
	private final OffsetDateTime approvedAt;
	private final OffsetDateTime completedAt;
	private final OffsetDateTime canceledAt;

	public PaymentResponse(Payment payment) {
		this.paymentKey = payment.getPaymentKey();
		this.paymentMethod = payment.getPaymentMethod();
		this.orderId = payment.getOrderId();
		this.status = payment.getStatus();
		this.amount = payment.getAmount();
		this.reservationId = payment.getReservationId();
		this.approvedAt = toOffsetDateTime(payment.getApprovedAt());
		this.completedAt = toOffsetDateTime(payment.getCompletedAt());
		this.canceledAt = toOffsetDateTime(payment.getCanceledAt());
	}

	private static OffsetDateTime toOffsetDateTime(java.time.LocalDateTime ldt) {
		return ldt == null ? null : ldt.atZone(SEOUL).toOffsetDateTime();
	}

	public String getPaymentKey() {
		return paymentKey;
	}

	public PaymentMethod getPaymentMethod() {
		return paymentMethod;
	}

	public String getOrderId() {
		return orderId;
	}

	public PaymentStatus getStatus() {
		return status;
	}

	public Long getAmount() {
		return amount;
	}

	public Long getReservationId() {
		return reservationId;
	}

	public OffsetDateTime getApprovedAt() {
		return approvedAt;
	}

	public OffsetDateTime getCompletedAt() {
		return completedAt;
	}

	public OffsetDateTime getCanceledAt() {
		return canceledAt;
	}
}
