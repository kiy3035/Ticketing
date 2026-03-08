package com.inyoung.ticketing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// application.properties의 ticketing.* 설정 바인딩용 클래스
@ConfigurationProperties(prefix = "ticketing")
public class TicketingProperties {
	private Hold hold = new Hold();
	private Lock lock = new Lock();
	private Kafka kafka = new Kafka();
	private Queue queue = new Queue();
	private Payment payment = new Payment();
	private Refund refund = new Refund();

	// 홀드 관련 설정 접근자
	public Hold getHold() {
		return hold;
	}

	// 좌석 락 관련 설정 접근자
	public Lock getLock() {
		return lock;
	}

	public Refund getRefund() {
		return refund;
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
		/** 홀드 정리 배치 한 번에 처리할 만료 홀드 수. 기본 200. */
		private int cleanupBatchSize = 200;

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

		public int getCleanupBatchSize() {
			return cleanupBatchSize;
		}

		public void setCleanupBatchSize(int cleanupBatchSize) {
			this.cleanupBatchSize = cleanupBatchSize;
		}
	}

	/** 좌석 동시 선점용 Redis 락 설정. 키: lock:seat:{seatId}, TTL 초과 시 자동 해제 */
	public static class Lock {
		/** 좌석 락 유지 시간(초). 홀드/예약 확정 시 해당 좌석 락 TTL. 기본 5초 */
		private long ttlSeconds = 5;
		/** 락 획득 실패 시 재시도 횟수. 0이면 재시도 없음. 기본 0 */
		private int retryCount = 0;
		/** 재시도 간 대기 시간(밀리초). 기본 50 */
		private long retryDelayMs = 50;

		public long getTtlSeconds() {
			return ttlSeconds;
		}

		public void setTtlSeconds(long ttlSeconds) {
			this.ttlSeconds = ttlSeconds;
		}

		public int getRetryCount() {
			return retryCount;
		}

		public void setRetryCount(int retryCount) {
			this.retryCount = retryCount;
		}

		public long getRetryDelayMs() {
			return retryDelayMs;
		}

		public void setRetryDelayMs(long retryDelayMs) {
			this.retryDelayMs = retryDelayMs;
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
		/** 대기 인원이 이 값 이하이고 예매 가능 좌석이 있으면 진입 시 즉시 입장 허용 (0이면 비활성화) */
		private int immediateAllowThreshold = 30;
		/** 대기 인원이 이 값 초과일 때만 대기열 필요(패턴 B). 이하면 바로 좌석 페이지 진입 가능. 0이면 항상 대기열 없음. */
		private int activationThreshold = 50;

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

		public int getImmediateAllowThreshold() {
			return immediateAllowThreshold;
		}

		public void setImmediateAllowThreshold(int immediateAllowThreshold) {
			this.immediateAllowThreshold = immediateAllowThreshold;
		}

		public int getActivationThreshold() {
			return activationThreshold;
		}

		public void setActivationThreshold(int activationThreshold) {
			this.activationThreshold = activationThreshold;
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

	// 취소된 공연 환불 배치 설정
	public static class Refund {
		/** 한 번에 처리할 결제 건수. 기본 50. */
		private int batchSize = 50;
		/** 배치 실행 주기(밀리초). 기본 5분. */
		private long intervalMs = 300_000;

		public int getBatchSize() {
			return batchSize;
		}

		public void setBatchSize(int batchSize) {
			this.batchSize = batchSize;
		}

		public long getIntervalMs() {
			return intervalMs;
		}

		public void setIntervalMs(long intervalMs) {
			this.intervalMs = intervalMs;
		}
	}
}
