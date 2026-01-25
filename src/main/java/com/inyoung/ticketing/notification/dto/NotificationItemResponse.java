package com.inyoung.ticketing.notification.dto;

import java.time.Instant;

// 알림 아이템 DTO
public class NotificationItemResponse {
	private String type;
	private String message;
	private Instant createdAt;

	public NotificationItemResponse() {
	}

	public NotificationItemResponse(String type, String message, Instant createdAt) {
		this.type = type;
		this.message = message;
		this.createdAt = createdAt;
	}

	public String getType() {
		return type;
	}

	public String getMessage() {
		return message;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
