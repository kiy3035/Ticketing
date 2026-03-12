package com.inyoung.ticketing.payment.domain;

/**
 * 결제 수단 구분.
 * <ul>
 *   <li>{@link #POINT}: 회원 보유 포인트에서 차감. 가입 시 지급 포인트 포함.</li>
 *   <li>{@link #CARD}: 토스페이먼츠 결제창으로 카드 결제. 샌드박스 사용 시 모의결제(실제 출금 없음).</li>
 * </ul>
 */
public enum PaymentMethod {
	/** 포인트 결제: users.point 차감 후 예약 확정 */
	POINT,
	/** 카드 결제: 토스페이먼츠 승인 API 호출 후 예약 확정 (포인트 미차감) */
	CARD
}
