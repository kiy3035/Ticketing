package com.inyoung.ticketing.dto;

import java.time.Instant;

// 알림 아이템 DTO
public class NotificationItem {
	private String type;
	private String message;
	private Instant createdAt;

	public NotificationItem() {
	}

	public NotificationItem(String type, String message, Instant createdAt) {
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
