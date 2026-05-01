package com.inyoung.ticketing.payment.domain;

import java.time.LocalDateTime;
import com.inyoung.ticketing.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * ════════════════════════════════════════════════════════════════
 * [Payment 엔티티 — 결제]
 * 상태 흐름: READY → APPROVED → COMPLETED / CANCELED
 * 결제 수단: POINT(포인트 차감) / CARD(토스 모의결제)
 *
 * ■ 연관 엔티티 참조 없이 primitive Long 사용 (concertId, seatId, userId, reservationId)
 *
 *   일반적으로 JPA에서 연관 엔티티는 @ManyToOne으로 선언하는 것이 정석이다.
 *   여기서는 의도적으로 원시 Long 필드를 사용했다. 이유:
 *
 *   1. 결제 도메인은 Concert, Seat, Users 객체 자체가 필요 없다.
 *      금액, 상태, 결제 키만 다루면 되므로 불필요한 조인을 없앤 것.
 *   2. 결제 이력은 장기 보관 데이터다. 나중에 Concert나 Seat이 삭제되더라도
 *      결제 기록은 남아있어야 하는데, FK로 걸면 참조 무결성 위반이 생긴다.
 *   3. 마이크로서비스로 분리를 고려할 때, 각 서비스의 엔티티를 직접 참조하지 않는
 *      "느슨한 결합" 설계와 일치한다.
 *
 *   단점:
 *   - DB 레벨 FK 제약이 없어서 존재하지 않는 concertId로 결제 레코드가 생길 수 있다.
 *   - 애플리케이션 레이어에서 반드시 유효성 검증을 해야 한다.
 *
 * ■ uniqueConstraints on payment_key, hold_token
 *   - payment_key: 우리 시스템이 결제 생성 시 발급하는 고유 키 (UUID 기반).
 *     동일 payment_key로 두 번 결제가 되어서는 안 된다 → DB 유니크 보장.
 *   - hold_token: Redis에서 좌석을 홀드할 때 발급한 토큰.
 *     하나의 홀드 토큰으로 하나의 결제만 가능해야 한다 → 중복 결제 방지.
 *   - 효과: 동시에 동일 키로 INSERT가 들어와도 DB가 하나만 허용 → idempotency 보장.
 *   - 단점: DataIntegrityViolationException 처리를 서비스에서 해야 한다.
 * ════════════════════════════════════════════════════════════════
 */
@Entity
@Table(
	name = "payment",
	uniqueConstraints = {
		@UniqueConstraint(columnNames = { "payment_key" }),
		@UniqueConstraint(columnNames = { "hold_token" })
	}
)
public class Payment extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * 우리 시스템 고유 결제 식별자 (UUID 등).
	 * paymentKey는 결제 생성 시 서버에서 생성해 클라이언트에 전달하고,
	 * 이후 조회/취소 시 이 키를 기준으로 찾는다.
	 */
	@Column(name = "payment_key", nullable = false, length = 40)
	private String paymentKey;

	/**
	 * 좌석 홀드 토큰. Redis에서 발급한 값을 그대로 저장.
	 * 결제 완료 시 이 토큰으로 어떤 좌석 홀드와 연결된 결제인지 찾는다.
	 */
	@Column(name = "hold_token", nullable = false, length = 64)
	private String holdToken;

	@Column(name = "user_id", nullable = false, length = 64)
	private String userId;

	/**
	 * 콘서트 ID (원시 타입).
	 * @ManyToOne Concert 대신 ID만 저장하는 이유: 결제 처리에서 Concert 객체 불필요.
	 * (위 클래스 주석의 "primitive Long 사용" 설명 참조)
	 */
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

	/**
	 * 결제 상태.
	 * READY: 결제 생성됨
	 * APPROVED: 토스 승인 완료 (CARD 전용 중간 상태)
	 * COMPLETED: 예약 확정까지 완료된 최종 성공 상태
	 * CANCELED: 취소됨
	 */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PaymentStatus status = PaymentStatus.READY;

	/**
	 * 연결된 예약 ID. 결제 완료(COMPLETED) 시점에 set됨.
	 * 결제 생성 시점에는 아직 예약이 없으므로 nullable.
	 */
	@Column(name = "reservation_id")
	private Long reservationId;

	/** 승인 시각 (서울 시간, DB DATETIME 저장) */
	@Column(name = "approved_at")
	private LocalDateTime approvedAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	@Column(name = "canceled_at")
	private LocalDateTime canceledAt;

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
}
