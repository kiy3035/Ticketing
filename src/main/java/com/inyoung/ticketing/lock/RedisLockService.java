package com.inyoung.ticketing.lock;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

// Redis 기반 분산 락 구현
@Service
public class RedisLockService implements LockService {
	// 토큰 일치 시에만 락을 해제하는 Lua 스크립트
	private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
		"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
		Long.class
	);

	private final StringRedisTemplate redisTemplate;

	// Redis 템플릿 주입
	public RedisLockService(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	@Override
	// TTL을 가진 락 획득 시도
	public Optional<String> tryLock(String key, Duration ttl) {
		String token = UUID.randomUUID().toString();
		Boolean success = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
		if (Boolean.TRUE.equals(success)) {
			return Optional.of(token);
		}
		return Optional.empty();
	}

	@Override
	// 토큰이 일치하면 락 해제
	public boolean unlock(String key, String token) {
		Long result = redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
		return result != null && result > 0;
	}
}
