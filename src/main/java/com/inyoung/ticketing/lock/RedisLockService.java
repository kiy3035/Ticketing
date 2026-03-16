package com.inyoung.ticketing.lock;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

// Redis 기반 분산 락 구현.
// 좌석 락 등 "짧은 TTL + 소유자 토큰" 패턴의 락을 제공해
// 다중 애플리케이션 인스턴스 간에 공통된 락 메커니즘을 사용하게 한다.
@Service
public class RedisLockService implements LockService {
	// 토큰이 일치할 때에만 락을 해제하는 Lua 스크립트.
	// 단일 명령으로 GET/DEL을 수행해 "다른 쓰레드가 덮어쓴 락을 실수로 해제"하는 상황을 방지한다.
	private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
		"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
		Long.class
	);

	private final StringRedisTemplate redisTemplate;

	public RedisLockService(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	@Override
	// TTL을 가진 분산 락 획득 시도.
	// 성공 시에는 이후 unlock 시에만 사용하는 토큰을 함께 반환하고,
	// 실패 시에는 Optional.empty()를 반환해 호출 측에서 재시도/실패 응답을 결정하게 한다.
	public Optional<String> tryLock(String key, Duration ttl) {
		String token = UUID.randomUUID().toString();
		Boolean success = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
		if (Boolean.TRUE.equals(success)) {
			return Optional.of(token);
		}
		return Optional.empty();
	}

	@Override
	// 토큰이 일치하는 경우에만 락을 해제한다.
	// (이미 TTL 만료 후 다른 소유자가 같은 key로 새 락을 잡은 경우, 그 락은 절대 건드리지 않는다.)
	public boolean unlock(String key, String token) {
		Long result = redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
		return result != null && result > 0;
	}
}
