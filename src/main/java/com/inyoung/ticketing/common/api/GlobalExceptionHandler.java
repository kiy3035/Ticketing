package com.inyoung.ticketing.common.api;

import jakarta.servlet.http.HttpServletRequest;
import com.inyoung.ticketing.common.util.TimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

// 공통 예외 처리 핸들러
@RestControllerAdvice
public class GlobalExceptionHandler {
	private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ErrorResponse> handleResponseStatus(
		ResponseStatusException ex,
		HttpServletRequest request
	) {
		HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
		return ResponseEntity.status(status)
			.body(buildError(status, ex.getReason(), request));
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
			.body(buildError(status, message, request));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(
		Exception ex,
		HttpServletRequest request
	) {
		logger.error("Unexpected error on {}", request.getRequestURI(), ex);
		HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
		return ResponseEntity.status(status)
			.body(buildError(status, "Unexpected error", request));
	}

	private ErrorResponse buildError(HttpStatus status, String message, HttpServletRequest request) {
		return new ErrorResponse(
			status.value(),
			status.getReasonPhrase(),
			message == null ? status.getReasonPhrase() : message,
			request.getRequestURI(),
			TimeUtils.nowKst()
		);
	}
}
