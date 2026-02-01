package com.inyoung.ticketing.notification.controller;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.inyoung.ticketing.notification.service.SseNotificationService;

// SSE 기반 실시간 알림 엔드포인트
@RestController
@RequestMapping("/api/notifications")
public class NotificationSseController {
	private final SseNotificationService sseNotificationService;

	public NotificationSseController(SseNotificationService sseNotificationService) {
		this.sseNotificationService = sseNotificationService;
	}

	// SSE 연결 생성
	@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter streamNotifications(Authentication authentication) {
		if (authentication == null) {
			throw new IllegalStateException("Authentication required");
		}
		String userId = authentication.getName();
		return sseNotificationService.createConnection(userId);
	}
}
