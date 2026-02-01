package com.inyoung.ticketing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// application.properties의 ticketing.* 설정 바인딩용 클래스
@ConfigurationProperties(prefix = "ticketing")
public class TicketingProperties {
	private Hold hold = new Hold();
	private Kafka kafka = new Kafka();
	private Queue queue = new Queue();

	// 홀드 관련 설정 접근자
	public Hold getHold() {
		return hold;
	}

	public Kafka getKafka() {
		return kafka;
	}

	// 대기열 관련 설정 접근자
	public Queue getQueue() {
		return queue;
	}

	// 홀드 설정 묶음
	public static class Hold {
		private long ttlSeconds = 300;
		private long cleanupIntervalMs = 60000;

		// 홀드 TTL(초)
		public long getTtlSeconds() {
			return ttlSeconds;
		}

		// 홀드 TTL 설정
		public void setTtlSeconds(long ttlSeconds) {
			this.ttlSeconds = ttlSeconds;
		}

		// 홀드 정리 스케줄러 주기(밀리초)
		public long getCleanupIntervalMs() {
			return cleanupIntervalMs;
		}

		// 홀드 정리 스케줄러 주기 설정
		public void setCleanupIntervalMs(long cleanupIntervalMs) {
			this.cleanupIntervalMs = cleanupIntervalMs;
		}
	}

	// Kafka 설정 묶음
	public static class Kafka {
		private String holdTopic = "ticketing.seat-hold-events";

		public String getHoldTopic() {
			return holdTopic;
		}

		public void setHoldTopic(String holdTopic) {
			this.holdTopic = holdTopic;
		}
	}

	// 대기열 설정 묶음
	public static class Queue {
		private int batchSize = 50;
		private long processingIntervalMs = 2000;
		private long tokenTtlSeconds = 1800;

		// 배치 크기 (한 번에 처리할 사용자 수)
		public int getBatchSize() {
			return batchSize;
		}

		// 배치 크기 설정
		public void setBatchSize(int batchSize) {
			this.batchSize = batchSize;
		}

		// 처리 주기(밀리초)
		public long getProcessingIntervalMs() {
			return processingIntervalMs;
		}

		// 처리 주기 설정
		public void setProcessingIntervalMs(long processingIntervalMs) {
			this.processingIntervalMs = processingIntervalMs;
		}

		// 토큰 TTL(초)
		public long getTokenTtlSeconds() {
			return tokenTtlSeconds;
		}

		// 토큰 TTL 설정
		public void setTokenTtlSeconds(long tokenTtlSeconds) {
			this.tokenTtlSeconds = tokenTtlSeconds;
		}
	}
}
