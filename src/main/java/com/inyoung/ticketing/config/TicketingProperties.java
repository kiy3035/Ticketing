package com.inyoung.ticketing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// application.properties의 ticketing.* 설정 바인딩용 클래스
@ConfigurationProperties(prefix = "ticketing")
public class TicketingProperties {
	private Hold hold = new Hold();
	private Kafka kafka = new Kafka();
	private Queue queue = new Queue();
	private Payment payment = new Payment();

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

	// 결제 관련 설정 접근자 (결제 진행 중 홀드 연장 등)
	public Payment getPayment() {
		return payment;
	}

	// 홀드 설정 묶음 (좌석 선택 단계 TTL)
	public static class Hold {
		/** 좌석 선택 단계 홀드 유효 시간(초). 기본 10분. */
		private long ttlSeconds = 600;
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
		private long cleanupIntervalMs = 60000;
		private int cleanupBatchSize = 200;

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

		// 만료 토큰 정리 주기(밀리초)
		public long getCleanupIntervalMs() {
			return cleanupIntervalMs;
		}

		// 만료 토큰 정리 주기 설정
		public void setCleanupIntervalMs(long cleanupIntervalMs) {
			this.cleanupIntervalMs = cleanupIntervalMs;
		}

		// 한 번에 정리할 토큰 수
		public int getCleanupBatchSize() {
			return cleanupBatchSize;
		}

		// 정리 배치 크기 설정
		public void setCleanupBatchSize(int cleanupBatchSize) {
			this.cleanupBatchSize = cleanupBatchSize;
		}
	}

	// 결제 설정 묶음 (결제 진행 중 홀드 연장 TTL)
	public static class Payment {
		/** 결제 요청 시 홀드 TTL 연장 시간(초). 기본 20분. */
		private long holdExtensionTtlSeconds = 1200;

		public long getHoldExtensionTtlSeconds() {
			return holdExtensionTtlSeconds;
		}

		public void setHoldExtensionTtlSeconds(long holdExtensionTtlSeconds) {
			this.holdExtensionTtlSeconds = holdExtensionTtlSeconds;
		}
	}
}
