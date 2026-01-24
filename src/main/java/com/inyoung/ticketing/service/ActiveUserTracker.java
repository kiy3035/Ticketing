package com.inyoung.ticketing.service;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

// Redis 기반 실시간 접속자 추적 서비스
@Service
public class ActiveUserTracker {
	private static final String ACTIVE_USERS_KEY = "active:users";
	private static final long WINDOW_SECONDS = 300; // 5분 기준

	private final StringRedisTemplate redisTemplate;

	public ActiveUserTracker(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	// 로그인/활동 시점 기록
	public void recordActive(String userId) {
		long now = System.currentTimeMillis();
		redisTemplate.opsForZSet().add(ACTIVE_USERS_KEY, userId, now);
		cleanupOld(now);
		redisTemplate.expire(ACTIVE_USERS_KEY, Duration.ofHours(1));
	}

	// 로그아웃 시 제거
	public void removeActive(String userId) {
		redisTemplate.opsForZSet().remove(ACTIVE_USERS_KEY, userId);
	}

	// 최근 WINDOW 내 접속자 수
	public long countActive() {
		long now = System.currentTimeMillis();
		cleanupOld(now);
		Long count = redisTemplate.opsForZSet()
			.count(ACTIVE_USERS_KEY, now - WINDOW_SECONDS * 1000, now);
		return count == null ? 0 : count;
	}

	private void cleanupOld(long now) {
		redisTemplate.opsForZSet().removeRangeByScore(
			ACTIVE_USERS_KEY, 0, now - WINDOW_SECONDS * 1000
		);
	}
}
