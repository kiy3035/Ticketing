package com.inyoung.ticketing.queue;

import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

// Redis 기반 대기열 스텁 서비스
@Service
public class QueueService {
	private static final String QUEUE_RANK_KEY = "queue:rank";
	private static final String QUEUE_TOKEN_KEY_PREFIX = "queue:token:";

	private final StringRedisTemplate redisTemplate;

	// Redis 템플릿 주입
	public QueueService(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	// 대기열 토큰 발급
	public String issueToken(String userId) {
		String token = UUID.randomUUID().toString();
		String tokenKey = QUEUE_TOKEN_KEY_PREFIX + token;
		long now = System.currentTimeMillis();

		redisTemplate.opsForValue().set(tokenKey, userId, Duration.ofMinutes(10));
		redisTemplate.opsForZSet().add(QUEUE_RANK_KEY, token, now);

		return token;
	}

	// 대기 순번 조회
	public Long getRank(String token) {
		Long rank = redisTemplate.opsForZSet().rank(QUEUE_RANK_KEY, token);
		return rank != null ? rank + 1 : null;
	}

	// 대기열 사용자 수
	public long countWaiting() {
		Long size = redisTemplate.opsForZSet().size(QUEUE_RANK_KEY);
		return size == null ? 0 : size;
	}
}
