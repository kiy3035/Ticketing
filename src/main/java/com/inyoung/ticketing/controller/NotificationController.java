package com.inyoung.ticketing.controller;

import java.util.List;
import com.inyoung.ticketing.dto.NotificationItem;
import com.inyoung.ticketing.dto.NotificationResponse;
import com.inyoung.ticketing.service.NotificationService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 알림 조회/삭제 API
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
	private final NotificationService notificationService;

	public NotificationController(NotificationService notificationService) {
		this.notificationService = notificationService;
	}

	@GetMapping
	public NotificationResponse list(Authentication authentication) {
		List<NotificationItem> items = notificationService.getNotifications(authentication.getName());
		return new NotificationResponse(items.size(), items);
	}

	@DeleteMapping
	public void clear(Authentication authentication) {
		notificationService.clearNotifications(authentication.getName());
	}
}
