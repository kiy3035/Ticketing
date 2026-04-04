package com.inyoung.ticketing.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 도메인 에러 코드.
 * HTTP 상태, 고유 코드, 기본 메시지를 하나로 묶어
 * 서비스 계층에서 throw, GlobalExceptionHandler에서 변환하는 패턴을 사용한다.
 *
 * <p>코드 체계:
 * <ul>
 *   <li>SEAT_xxx : 좌석/홀드 관련</li>
 *   <li>PAY_xxx  : 결제 관련</li>
 *   <li>RSV_xxx  : 예약 관련</li>
 *   <li>AUTH_xxx : 인증/인가 관련</li>
 *   <li>QUEUE_xxx: 대기열 관련</li>
 *   <li>COMMON_xxx: 공통</li>
 * </ul>
 */
public enum ErrorCode {

	// ── 좌석/홀드 ──
	SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "SEAT_001", "좌석을 찾을 수 없습니다"),
	SEAT_ALREADY_RESERVED(HttpStatus.CONFLICT, "SEAT_002", "이미 예약된 좌석입니다"),
	SEAT_ALREADY_HELD(HttpStatus.CONFLICT, "SEAT_003", "이미 선점된 좌석입니다"),
	SEAT_BUSY(HttpStatus.TOO_MANY_REQUESTS, "SEAT_004", "좌석 경합이 발생했습니다. 잠시 후 재시도하세요"),

	HOLD_NOT_FOUND(HttpStatus.NOT_FOUND, "SEAT_010", "홀드 정보를 찾을 수 없습니다"),
	HOLD_EXPIRED(HttpStatus.CONFLICT, "SEAT_011", "홀드가 만료되었습니다"),
	HOLD_OWNER_MISMATCH(HttpStatus.CONFLICT, "SEAT_012", "홀드 소유자가 일치하지 않습니다"),

	// ── 결제 ──
	PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAY_001", "결제 정보를 찾을 수 없습니다"),
	PAYMENT_ALREADY_COMPLETED(HttpStatus.CONFLICT, "PAY_002", "이미 완료된 결제입니다"),
	PAYMENT_ALREADY_CANCELED(HttpStatus.CONFLICT, "PAY_003", "이미 취소된 결제입니다"),
	PAYMENT_NOT_APPROVED(HttpStatus.CONFLICT, "PAY_004", "결제가 승인 상태가 아닙니다"),
	PAYMENT_OWNER_MISMATCH(HttpStatus.CONFLICT, "PAY_005", "결제 소유자가 일치하지 않습니다"),
	INSUFFICIENT_POINTS(HttpStatus.CONFLICT, "PAY_006", "포인트가 부족합니다"),
	CARD_APPROVE_INVALID(HttpStatus.BAD_REQUEST, "PAY_007", "카드 결제 승인 정보가 유효하지 않습니다"),
	ORDER_AMOUNT_MISMATCH(HttpStatus.CONFLICT, "PAY_008", "주문 정보가 일치하지 않습니다"),
	IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "PAY_009", "동일한 요청이 처리 중입니다"),

	// ── 예약 ──
	RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "RSV_001", "예약 정보를 찾을 수 없습니다"),
	CONCERT_CANCELLED(HttpStatus.BAD_REQUEST, "RSV_002", "취소된 공연입니다"),
	CONCERT_PAST(HttpStatus.BAD_REQUEST, "RSV_003", "이미 지난 공연은 예약할 수 없습니다"),
	CONCERT_MISMATCH(HttpStatus.CONFLICT, "RSV_004", "콘서트 정보가 일치하지 않습니다"),

	// ── 인증/인가 ──
	USER_NOT_FOUND(HttpStatus.NOT_FOUND, "AUTH_001", "사용자를 찾을 수 없습니다"),
	USERNAME_DUPLICATE(HttpStatus.CONFLICT, "AUTH_002", "이미 존재하는 사용자 아이디입니다"),

	// ── 대기열 ──
	QUEUE_TOKEN_EXPIRED(HttpStatus.GONE, "QUEUE_001", "대기열 토큰이 만료되었습니다"),

	// ── 공통 ──
	RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "COMMON_001", "요청 한도를 초과했습니다. 잠시 후 재시도하세요"),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_999", "서버 내부 오류가 발생했습니다");

	private final HttpStatus httpStatus;
	private final String code;
	private final String defaultMessage;

	ErrorCode(HttpStatus httpStatus, String code, String defaultMessage) {
		this.httpStatus = httpStatus;
		this.code = code;
		this.defaultMessage = defaultMessage;
	}

	public HttpStatus getHttpStatus() {
		return httpStatus;
	}

	public String getCode() {
		return code;
	}

	public String getDefaultMessage() {
		return defaultMessage;
	}
}
