package com.inyoung.ticketing.lock;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class RedisLockService implements LockService {
	private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
		"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
		Long.class
	);

	private final StringRedisTemplate redisTemplate;

	public RedisLockService(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	@Override
	public Optional<String> tryLock(String key, Duration ttl) {
		String token = UUID.randomUUID().toString();
		Boolean success = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
		if (Boolean.TRUE.equals(success)) {
			return Optional.of(token);
		}
		return Optional.empty();
	}

	@Override
	public boolean unlock(String key, String token) {
		Long result = redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
		return result != null && result > 0;
	}
}
