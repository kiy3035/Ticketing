package com.inyoung.ticketing.health;

import javax.sql.DataSource;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;

/**
 * DB 연결 풀 상태를 /actuator/health에 노출한다.
 * 장애 시 "database" down으로 원인 파악에 활용할 수 있다.
 */
@Component("database")
public class DatabaseHealthIndicator extends AbstractHealthIndicator {

	private final DataSource dataSource;

	public DatabaseHealthIndicator(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	protected void doHealthCheck(Health.Builder builder) {
		try {
			try (var conn = dataSource.getConnection()) {
				boolean valid = conn.isValid(3);
				if (valid) {
					builder.up()
						.withDetail("database", conn.getCatalog())
						.withDetail("valid", true);
				} else {
					builder.down().withDetail("message", "Connection validation failed");
				}
			}
		} catch (Exception e) {
			builder.down()
				.withException(e)
				.withDetail("message", "Database connection failed");
		}
	}
}
