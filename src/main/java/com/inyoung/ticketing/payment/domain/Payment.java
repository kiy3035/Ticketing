package com.inyoung.ticketing.payment.domain;

import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 결제 엔티티. READY → APPROVED → COMPLETED. paymentMethod 에 따라 포인트 차감 또는 토스 승인 연동.
 * CARD 시 orderId(위젯 requestPayment 용), tossPaymentKey(토스 승인 후 저장) 사용. reservationId 는 complete 시 설정.
 */
@Entity
@Table(
	name = "payment",
	uniqueConstraints = {
		@UniqueConstraint(columnNames = { "payment_key" }),
		@UniqueConstraint(columnNames = { "hold_token" })
	}
)
public class Payment {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "payment_key", nullable = false, length = 40)
	private String paymentKey;

	@Column(name = "hold_token", nullable = false, length = 64)
	private String holdToken;

	@Column(name = "user_id", nullable = false, length = 64)
	private String userId;

	@Column(name = "concert_id", nullable = false)
	private Long concertId;

	@Column(name = "seat_id", nullable = false)
	private Long seatId;

	@Column(nullable = false)
	private Long amount;

	/** 결제 수단: POINT(포인트 차감) / CARD(토스 모의결제) */
	@Enumerated(EnumType.STRING)
	@Column(name = "payment_method", nullable = false, length = 20)
	private PaymentMethod paymentMethod = PaymentMethod.POINT;

	/**
	 * 토스 주문 ID. CARD 결제 시에만 설정.
	 * 결제 요청 시 생성하여 프론트에 전달하고, 토스 결제창·승인 API에서 동일 값 사용.
	 */
	@Column(name = "order_id", length = 64)
	private String orderId;

	/**
	 * 토스에서 발급한 결제 키. CARD 결제 승인 후 저장.
	 * 취소 API 호출 시 사용할 수 있음 (현재 취소는 우리 DB 상태만 CANCELED 처리).
	 */
	@Column(name = "toss_payment_key", length = 64)
	private String tossPaymentKey;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PaymentStatus status = PaymentStatus.READY;

	@Column(name = "reservation_id")
	private Long reservationId;

	/** 승인 시각 (서울 시간, DB DATETIME 저장) */
	@Column(name = "approved_at")
	private LocalDateTime approvedAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	@Column(name = "canceled_at")
	private LocalDateTime canceledAt;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(nullable = false)
	private LocalDateTime updatedAt;

	@PrePersist
	void prePersist() {
		LocalDateTime now = LocalDateTime.now().withNano(0);
		this.createdAt = now;
		this.updatedAt = now;
	}

	@PreUpdate
	void preUpdate() {
		this.updatedAt = LocalDateTime.now().withNano(0);
	}

	public Long getId() {
		return id;
	}

	public String getPaymentKey() {
		return paymentKey;
	}

	public void setPaymentKey(String paymentKey) {
		this.paymentKey = paymentKey;
	}

	public String getHoldToken() {
		return holdToken;
	}

	public void setHoldToken(String holdToken) {
		this.holdToken = holdToken;
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

	public Long getSeatId() {
		return seatId;
	}

	public void setSeatId(Long seatId) {
		this.seatId = seatId;
	}

	public Long getAmount() {
		return amount;
	}

	public void setAmount(Long amount) {
		this.amount = amount;
	}

	public PaymentMethod getPaymentMethod() {
		return paymentMethod;
	}

	public void setPaymentMethod(PaymentMethod paymentMethod) {
		this.paymentMethod = paymentMethod;
	}

	public String getOrderId() {
		return orderId;
	}

	public void setOrderId(String orderId) {
		this.orderId = orderId;
	}

	public String getTossPaymentKey() {
		return tossPaymentKey;
	}

	public void setTossPaymentKey(String tossPaymentKey) {
		this.tossPaymentKey = tossPaymentKey;
	}

	public PaymentStatus getStatus() {
		return status;
	}

	public void setStatus(PaymentStatus status) {
		this.status = status;
	}

	public Long getReservationId() {
		return reservationId;
	}

	public void setReservationId(Long reservationId) {
		this.reservationId = reservationId;
	}

	public LocalDateTime getApprovedAt() {
		return approvedAt;
	}

	public void setApprovedAt(LocalDateTime approvedAt) {
		this.approvedAt = approvedAt;
	}

	public LocalDateTime getCompletedAt() {
		return completedAt;
	}

	public void setCompletedAt(LocalDateTime completedAt) {
		this.completedAt = completedAt;
	}

	public LocalDateTime getCanceledAt() {
		return canceledAt;
	}

	public void setCanceledAt(LocalDateTime canceledAt) {
		this.canceledAt = canceledAt;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}
}
