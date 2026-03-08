package com.inyoung.ticketing.health;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.stereotype.Component;

/**
 * Kafka 브로커 연결 상태를 /actuator/health에 노출한다.
 * 장애 시 "kafka" down으로 원인 파악에 활용할 수 있다.
 */
@Component("kafka")
public class KafkaHealthIndicator extends AbstractHealthIndicator {

	private final KafkaProperties kafkaProperties;

	public KafkaHealthIndicator(KafkaProperties kafkaProperties) {
		this.kafkaProperties = kafkaProperties;
	}

	@Override
	protected void doHealthCheck(Health.Builder builder) {
		Map<String, Object> configs = new HashMap<>();
		configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
		configs.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
		try (AdminClient adminClient = AdminClient.create(configs)) {
			var options = new DescribeClusterOptions().timeoutMs(3000);
			var result = adminClient.describeCluster(options);
			String clusterId = result.clusterId().get(3, TimeUnit.SECONDS);
			int nodeCount = result.nodes().get(3, TimeUnit.SECONDS).size();
			builder.up()
				.withDetail("clusterId", clusterId != null ? clusterId : "unknown")
				.withDetail("nodeCount", nodeCount);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			builder.down().withException(e).withDetail("message", "Kafka check interrupted");
		} catch (ExecutionException | TimeoutException e) {
			builder.down().withException(e).withDetail("message", "Kafka broker unreachable");
		}
	}
}
