package com.inyoung.ticketing.health;

import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Actuator 전용 헬스: Redis + DB 를 한 컴포넌트로 묶어 노출한다.
 * <ul>
 *   <li>전역 설정에서 Kafka 헬스는 끈 상태이므로({@code management.health.kafka.enabled=false}),
 *       브로커 지연이 전체 readiness 를 죽이지 않게 한다. 이 인디케이터는 "세션·홀드·락·JPA" 코어만 본다.</li>
 *   <li>둘 중 하나라도 실패하면 {@link Health#down()} — 쿠버네티스 readiness 등에 엄격한 기준으로 쓸 수 있다.</li>
 * </ul>
 */
@Component("ticketingDatastores")
public class TicketingDatastoresHealthIndicator implements HealthIndicator {

	private final RedisConnectionFactory redisConnectionFactory;
	private final DataSource dataSource;

	public TicketingDatastoresHealthIndicator(
		RedisConnectionFactory redisConnectionFactory,
		DataSource dataSource
	) {
		this.redisConnectionFactory = redisConnectionFactory;
		this.dataSource = dataSource;
	}

	@Override
	public Health health() {
		RedisPing redisPing = pingRedis();
		DbValid dbValid = validateDb();
		if (redisPing.ok() && dbValid.ok()) {
			return Health.up()
				.withDetail("redis", "PONG")
				.withDetail("database", "valid")
				.build();
		}
		return Health.down()
			.withDetail("redis", redisPing.detail())
			.withDetail("database", dbValid.detail())
			.build();
	}

	private RedisPing pingRedis() {
		try (RedisConnection connection = redisConnectionFactory.getConnection()) {
			String pong = connection.ping();
			boolean ok = pong != null && "PONG".equalsIgnoreCase(pong);
			return new RedisPing(ok, ok ? "PONG" : "unexpected: " + pong);
		} catch (Exception e) {
			return new RedisPing(false, e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}

	private DbValid validateDb() {
		try (Connection c = dataSource.getConnection()) {
			boolean ok = c.isValid(2);
			return new DbValid(ok, ok ? "valid" : "isValid returned false");
		} catch (Exception e) {
			return new DbValid(false, e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}

	/** 헬스 판단용 소형 record — 메서드 로컬로만 쓰고 외부 API 로 노출하지 않는다. */
	private record RedisPing(boolean ok, String detail) {
	}

	private record DbValid(boolean ok, String detail) {
	}
}
