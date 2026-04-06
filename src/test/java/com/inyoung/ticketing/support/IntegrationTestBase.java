package com.inyoung.ticketing.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers 기반 통합 테스트 베이스 클래스.
 *
 * <p>MySQL, Redis, Kafka 컨테이너를 자동으로 띄우고
 * Spring 프로퍼티에 동적 바인딩한다.
 * CI 환경에서도 로컬 인프라 없이 테스트가 가능하다.</p>
 *
 * <p>사용법: 테스트 클래스에서 {@code extends IntegrationTestBase}만 하면 된다.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

	@Container
	static final MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
		.withDatabaseName("ticketing_test")
		.withUsername("test")
		.withPassword("test");

	@Container
	static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
		.withExposedPorts(6379);

	@Container
	static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

	@DynamicPropertySource
	static void configureProperties(DynamicPropertyRegistry registry) {
		// MySQL
		registry.add("spring.datasource.url", mysql::getJdbcUrl);
		registry.add("spring.datasource.username", mysql::getUsername);
		registry.add("spring.datasource.password", mysql::getPassword);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
		registry.add("spring.flyway.enabled", () -> "false");

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
