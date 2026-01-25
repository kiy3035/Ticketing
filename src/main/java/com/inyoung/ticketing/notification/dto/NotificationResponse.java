package com.inyoung.ticketing.notification.dto;

import java.util.List;

// 알림 목록 응답 DTO
public class NotificationResponse {
	private int unreadCount;
	private List<NotificationItemResponse> items;

	public NotificationResponse(int unreadCount, List<NotificationItemResponse> items) {
		this.unreadCount = unreadCount;
		this.items = items;
	}

	public int getUnreadCount() {
		return unreadCount;
	}

	public List<NotificationItemResponse> getItems() {
		return items;
	}
}
