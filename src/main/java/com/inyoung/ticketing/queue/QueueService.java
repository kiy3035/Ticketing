package com.inyoung.ticketing.queue;

import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class QueueService {
	private static final String QUEUE_RANK_KEY = "queue:rank";
	private static final String QUEUE_TOKEN_KEY_PREFIX = "queue:token:";

	private final StringRedisTemplate redisTemplate;

	public QueueService(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	public String issueToken(String userId) {
		String token = UUID.randomUUID().toString();
		String tokenKey = QUEUE_TOKEN_KEY_PREFIX + token;
		long now = System.currentTimeMillis();

		redisTemplate.opsForValue().set(tokenKey, userId, Duration.ofMinutes(10));
		redisTemplate.opsForZSet().add(QUEUE_RANK_KEY, token, now);

		return token;
	}

	public Long getRank(String token) {
		Long rank = redisTemplate.opsForZSet().rank(QUEUE_RANK_KEY, token);
		return rank != null ? rank + 1 : null;
	}
}
