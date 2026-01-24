package com.inyoung.ticketing.dto;

import java.util.List;

// 알림 목록 응답 DTO
public class NotificationResponse {
	private int unreadCount;
	private List<NotificationItem> items;

	public NotificationResponse(int unreadCount, List<NotificationItem> items) {
		this.unreadCount = unreadCount;
		this.items = items;
	}

	public int getUnreadCount() {
		return unreadCount;
	}

	public List<NotificationItem> getItems() {
		return items;
	}
}
