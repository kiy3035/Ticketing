package com.inyoung.ticketing.common.api;

import java.time.OffsetDateTime;

// 공통 에러 응답 DTO
public class ErrorResponse {
	private final int status;
	private final String error;
	private final String message;
	private final String path;
	private final OffsetDateTime timestamp;

	public ErrorResponse(int status, String error, String message, String path, OffsetDateTime timestamp) {
		this.status = status;
		this.error = error;
		this.message = message;
		this.path = path;
		this.timestamp = timestamp;
	}

	public int getStatus() {
		return status;
	}

	public String getError() {
		return error;
	}

	public String getMessage() {
		return message;
	}

	public String getPath() {
		return path;
	}

	public OffsetDateTime getTimestamp() {
		return timestamp;
	}
}
