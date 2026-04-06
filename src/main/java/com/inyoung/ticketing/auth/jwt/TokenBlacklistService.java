package com.inyoung.ticketing.auth.jwt;

import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 로그아웃된 Access 토큰의 jti 를 Redis 에 보관한다.
 * <p>
 * JWT 자체는 stateless 이지만, “만료 전까지 이 토큰은 거부”를 구현하려면 서버가 jti 를 기억해야 한다.
 * 키는 {@code jwt:bl:{jti}} 이고 TTL 은 해당 Access 의 남은 유효 시간과 맞춰 자동 삭제된다.
 * </p>
 */
@Service
public class TokenBlacklistService {
	private static final String PREFIX = "jwt:bl:";

	private final StringRedisTemplate redisTemplate;

	public TokenBlacklistService(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	/**
	 * 아직 만료되지 않은 Access 의 jti 만 블랙리스트에 넣는다. 이미 만료된 토큰은 넣을 필요가 없다.
	 *
	 * @param accessExpiresAt JWT exp 클레임 시각
	 */
	public void blacklistAccessJti(String jti, Instant accessExpiresAt) {
		if (jti == null || jti.isBlank()) {
			return;
		}
		long seconds = Duration.between(Instant.now(), accessExpiresAt).getSeconds();
		if (seconds <= 0) {
			return;
		}
		redisTemplate.opsForValue().set(PREFIX + jti, "1", Duration.ofSeconds(seconds));
	}

	/** 요청에 실린 Access jti 가 블랙리스트에 있으면 true */
	public boolean isAccessBlacklisted(String jti) {
		if (jti == null || jti.isBlank()) {
			return false;
		}
		return Boolean.TRUE.equals(redisTemplate.hasKey(PREFIX + jti));
	}
}
