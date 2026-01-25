package com.inyoung.ticketing.common.api;

import java.time.OffsetDateTime;
import com.inyoung.ticketing.common.util.TimeUtils;

// 공통 성공 응답 DTO
public class ApiResponse<T> {
	private final boolean success;
	private final T data;
	private final String message;
	private final OffsetDateTime timestamp;

	private ApiResponse(boolean success, T data, String message, OffsetDateTime timestamp) {
		this.success = success;
		this.data = data;
		this.message = message;
		this.timestamp = timestamp;
	}

	public static <T> ApiResponse<T> success(T data) {
		return new ApiResponse<>(true, data, "OK", TimeUtils.nowKst());
	}

	public boolean isSuccess() {
		return success;
	}

	public T getData() {
		return data;
	}

	public String getMessage() {
		return message;
	}

	public OffsetDateTime getTimestamp() {
		return timestamp;
	}
}
