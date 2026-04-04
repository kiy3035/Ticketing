package com.inyoung.ticketing.common.exception;

/**
 * 도메인 비즈니스 예외의 최상위 클래스.
 * {@link ErrorCode}를 포함해 GlobalExceptionHandler가 일관된 응답을 생성한다.
 *
 * <p>서비스 계층에서 {@code throw new BusinessException(ErrorCode.SEAT_ALREADY_HELD)}
 * 형태로 사용하며, 필요 시 추가 메시지를 덧붙일 수 있다.</p>
 */
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode) {
		super(errorCode.getDefaultMessage());
		this.errorCode = errorCode;
	}

	public BusinessException(ErrorCode errorCode, String detailMessage) {
		super(detailMessage);
		this.errorCode = errorCode;
	}

	public BusinessException(ErrorCode errorCode, Throwable cause) {
		super(errorCode.getDefaultMessage(), cause);
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}
}
