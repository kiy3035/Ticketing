package com.inyoung.ticketing.common.api;

import com.inyoung.ticketing.common.exception.BusinessException;
import com.inyoung.ticketing.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import com.inyoung.ticketing.common.util.TimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 전역 예외 처리.
 * {@link BusinessException}을 우선 처리하고, 기존 {@link ResponseStatusException}도 호환 유지한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
	private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusiness(
		BusinessException ex,
		HttpServletRequest request
	) {
		ErrorCode code = ex.getErrorCode();
		HttpStatus status = code.getHttpStatus();
		if (status.is5xxServerError()) {
			logger.error("[{}] {} - {}", code.getCode(), ex.getMessage(), request.getRequestURI(), ex);
		}
		return ResponseEntity.status(status)
			.body(buildError(status, code.getCode(), ex.getMessage(), request));
	}

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ErrorResponse> handleResponseStatus(
		ResponseStatusException ex,
		HttpServletRequest request
	) {
		HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
		return ResponseEntity.status(status)
			.body(buildError(status, null, ex.getReason(), request));
	}

	@ExceptionHandler(BadCredentialsException.class)
	public ResponseEntity<ErrorResponse> handleBadCredentials(
		BadCredentialsException ex,
		HttpServletRequest request
	) {
		HttpStatus status = HttpStatus.UNAUTHORIZED;
		return ResponseEntity.status(status)
			.body(buildError(status, "AUTH_401", "아이디 또는 비밀번호가 올바르지 않습니다.", request));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(
		MethodArgumentNotValidException ex,
		HttpServletRequest request
	) {
		HttpStatus status = HttpStatus.BAD_REQUEST;
		String message = ex.getBindingResult().getFieldErrors().stream()
			.findFirst()
			.map(error -> error.getField() + ": " + error.getDefaultMessage())
			.orElse("Validation failed");
		return ResponseEntity.status(status)
			.body(buildError(status, null, message, request));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(
		Exception ex,
		HttpServletRequest request
	) {
		logger.error("Unexpected error on {}", request.getRequestURI(), ex);
		HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
		return ResponseEntity.status(status)
			.body(buildError(status, "COMMON_999", "Unexpected error", request));
	}

	private ErrorResponse buildError(HttpStatus status, String code, String message, HttpServletRequest request) {
		return new ErrorResponse(
			status.value(),
			code != null ? code : status.getReasonPhrase(),
			message == null ? status.getReasonPhrase() : message,
			request.getRequestURI(),
			TimeUtils.nowKst()
		);
	}
}
