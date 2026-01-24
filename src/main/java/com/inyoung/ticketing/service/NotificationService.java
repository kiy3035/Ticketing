package com.inyoung.ticketing.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inyoung.ticketing.dto.NotificationItem;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

// 알림 저장/조회 서비스
@Service
public class NotificationService {
	private static final String KEY_PREFIX = "notify:user:";
	private static final int MAX_ITEMS = 50;
	private static final Duration TTL = Duration.ofDays(7);

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	public NotificationService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
	}

	public void addNotification(String userId, NotificationItem item) {
		String key = key(userId);
		redisTemplate.opsForList().leftPush(key, toJson(item));
		redisTemplate.opsForList().trim(key, 0, MAX_ITEMS - 1);
		redisTemplate.expire(key, TTL);
	}

	public List<NotificationItem> getNotifications(String userId) {
		String key = key(userId);
		List<String> raw = redisTemplate.opsForList().range(key, 0, -1);
		if (raw == null || raw.isEmpty()) {
			return Collections.emptyList();
		}
		List<NotificationItem> items = new ArrayList<>();
		for (String value : raw) {
			if (value == null || value.isBlank()) {
				continue;
			}
			items.add(fromJson(value));
		}
		return items;
	}

	public void clearNotifications(String userId) {
		redisTemplate.delete(key(userId));
	}

	private String key(String userId) {
		return KEY_PREFIX + userId;
	}

	private String toJson(NotificationItem item) {
		try {
			return objectMapper.writeValueAsString(item);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Notification serialize failed", e);
		}
	}

	private NotificationItem fromJson(String value) {
		try {
			return objectMapper.readValue(value, NotificationItem.class);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Notification parse failed", e);
		}
	}
}
