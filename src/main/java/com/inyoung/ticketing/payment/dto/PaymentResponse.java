package com.inyoung.ticketing.payment.dto;

import java.time.OffsetDateTime;
import com.inyoung.ticketing.payment.domain.Payment;
import com.inyoung.ticketing.payment.domain.PaymentStatus;

// 결제 응답 DTO
public class PaymentResponse {
	private final String paymentKey;
	private final PaymentStatus status;
	private final Long amount;
	private final Long reservationId;
	private final OffsetDateTime approvedAt;
	private final OffsetDateTime completedAt;
	private final OffsetDateTime canceledAt;

	public PaymentResponse(Payment payment) {
		this.paymentKey = payment.getPaymentKey();
		this.status = payment.getStatus();
		this.amount = payment.getAmount();
		this.reservationId = payment.getReservationId();
		this.approvedAt = payment.getApprovedAt();
		this.completedAt = payment.getCompletedAt();
		this.canceledAt = payment.getCanceledAt();
	}

	public String getPaymentKey() {
		return paymentKey;
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
