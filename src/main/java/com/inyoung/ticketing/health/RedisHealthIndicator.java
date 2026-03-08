package com.inyoung.ticketing.health;

import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Redis 연결 상태를 /actuator/health에 노출한다.
 * 장애 시 "redis" down으로 원인 파악에 활용할 수 있다.
 */
@Component("redis")
public class RedisHealthIndicator extends AbstractHealthIndicator {

	private final RedisConnectionFactory connectionFactory;

	public RedisHealthIndicator(RedisConnectionFactory connectionFactory) {
		this.connectionFactory = connectionFactory;
	}

	@Override
	protected void doHealthCheck(Health.Builder builder) {
		try {
			String pong = connectionFactory.getConnection().ping();
			builder.up()
				.withDetail("response", pong != null ? pong : "PONG");
		} catch (Exception e) {
			builder.down()
				.withException(e)
				.withDetail("message", "Redis connection failed");
		}
	}
}
