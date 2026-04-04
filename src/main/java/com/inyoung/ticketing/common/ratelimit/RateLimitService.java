package com.inyoung.ticketing.common.ratelimit;

import java.time.Instant;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * Redis Sorted Set 기반 Sliding Window Rate Limiter.
 *
 * <p>각 요청마다:
 * <ol>
 *   <li>윈도우 밖의 오래된 항목을 ZREMRANGEBYSCORE로 제거</li>
 *   <li>현재 윈도우 내 요청 수를 ZCARD로 카운트</li>
 *   <li>한도 이내면 ZADD로 현재 요청을 추가, 초과면 거부</li>
 * </ol>
 * 전체를 Lua 스크립트로 원자적으로 실행해 경쟁 조건을 방지한다.</p>
 */
@Service
public class RateLimitService {
	private static final String KEY_PREFIX = "ratelimit:";

	private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>(
		"""
		local key = KEYS[1]
		local window = tonumber(ARGV[1])
		local maxRequests = tonumber(ARGV[2])
		local now = tonumber(ARGV[3])
		local windowStart = now - window * 1000
		redis.call('ZREMRANGEBYSCORE', key, 0, windowStart)
		local count = redis.call('ZCARD', key)
		if count < maxRequests then
			redis.call('ZADD', key, now, now .. ':' .. math.random(1000000))
			redis.call('EXPIRE', key, window + 1)
			return 1
		end
		return 0
		""",
		Long.class
	);

	private final StringRedisTemplate redisTemplate;

	public RateLimitService(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	/**
	 * 요청이 허용되면 true, 한도 초과면 false.
	 *
	 * @param identifier 사용자 식별자 (username 또는 IP)
	 * @param maxRequests 윈도우 내 최대 요청 수
	 * @param windowSeconds 윈도우 크기(초)
	 */
	public boolean isAllowed(String identifier, int maxRequests, int windowSeconds) {
		String key = KEY_PREFIX + identifier;
		long now = Instant.now().toEpochMilli();
		Long result = redisTemplate.execute(
			RATE_LIMIT_SCRIPT,
			List.of(key),
			String.valueOf(windowSeconds),
			String.valueOf(maxRequests),
			String.valueOf(now)
		);
		return result != null && result == 1L;
	}
}
