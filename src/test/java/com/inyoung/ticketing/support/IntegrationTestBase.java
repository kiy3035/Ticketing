package com.inyoung.ticketing.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers 기반 통합 테스트 베이스 클래스.
 *
 * <p>컨테이너를 static 초기화 블록에서 한 번만 시작하고 JVM 종료 시까지 유지한다.
 * @Testcontainers + @Container 조합은 테스트 클래스마다 컨테이너를 재시작해
 * Spring Context Cache가 이전 포트를 참조하는 mismatch 문제가 생긴다.
 * static 싱글턴 방식으로 모든 테스트 클래스가 동일한 컨테이너 인스턴스·포트를 공유한다.</p>
 *
 * <p>사용법: 테스트 클래스에서 {@code extends IntegrationTestBase}만 하면 된다.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

	static final MySQLContainer<?> mysql;
	static final GenericContainer<?> redis;
	static final KafkaContainer kafka;

	static {
		mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
			.withDatabaseName("ticketing_test")
			.withUsername("test")
			.withPassword("test");

		redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

		kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

		mysql.start();
		redis.start();
		kafka.start();
	}

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		// MySQL
		registry.add("spring.datasource.url", mysql::getJdbcUrl);
		registry.add("spring.datasource.username", mysql::getUsername);
		registry.add("spring.datasource.password", mysql::getPassword);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
		registry.add("spring.flyway.enabled", () -> "false");

		// 동시성 테스트(100 스레드)에서 Hikari 기본 풀 크기(10)로는 connection timeout 발생.
		// 최대 풀을 스레드 수만큼 확보한다.
		registry.add("spring.datasource.hikari.maximum-pool-size", () -> "120");
		registry.add("spring.datasource.hikari.connection-timeout", () -> "10000");

		// Redis
		registry.add("spring.data.redis.host", redis::getHost);
		registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

		// Kafka
		registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);

		registry.add("ticketing.jwt.secret", () -> "test-jwt-secret-at-least-32-chars-long!!");

		// 테스트용 메일/SMS 무효화
		registry.add("spring.mail.host", () -> "localhost");
		registry.add("spring.mail.port", () -> "25");
		registry.add("MAIL_USERNAME", () -> "test@test.com");
		registry.add("MAIL_PASSWORD", () -> "test");
		registry.add("SOLAPI_API_KEY", () -> "test");
		registry.add("SOLAPI_API_SECRET", () -> "test");
		registry.add("SOLAPI_FROM_NUMBER", () -> "01000000000");
	}
}
