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
	private Toss toss = new Toss();
	private RateLimitProps rateLimit = new RateLimitProps();

	public Hold getHold() {
		return hold;
	}

	public Lock getLock() {
		return lock;
	}

	public Refund getRefund() {
		return refund;
	}

	public Kafka getKafka() {
		return kafka;
	}

	public Queue getQueue() {
		return queue;
	}

	public Payment getPayment() {
		return payment;
	}

	public Toss getToss() {
		return toss;
	}

	public void setToss(Toss toss) {
		this.toss = toss;
	}

	// 홀드 설정 묶음 (좌석 선택 단계 TTL)
	public static class Hold {
		/** 좌석 선택 단계 홀드 유효 시간(초). 기본 10분. */
		private long ttlSeconds = 600;
		private long cleanupIntervalMs = 60000;
		/** 홀드 정리 배치 한 번에 처리할 만료 홀드 수. 기본 200. */
		private int cleanupBatchSize = 200;

		public long getTtlSeconds() {
			return ttlSeconds;
		}

		public void setTtlSeconds(long ttlSeconds) {
			this.ttlSeconds = ttlSeconds;
		}

		public long getCleanupIntervalMs() {
			return cleanupIntervalMs;
		}

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

		public int getBatchSize() {
			return batchSize;
		}

		public void setBatchSize(int batchSize) {
			this.batchSize = batchSize;
		}

		public long getProcessingIntervalMs() {
			return processingIntervalMs;
		}

		public void setProcessingIntervalMs(long processingIntervalMs) {
			this.processingIntervalMs = processingIntervalMs;
		}

		public long getTokenTtlSeconds() {
			return tokenTtlSeconds;
		}

		public void setTokenTtlSeconds(long tokenTtlSeconds) {
			this.tokenTtlSeconds = tokenTtlSeconds;
		}

		public long getCleanupIntervalMs() {
			return cleanupIntervalMs;
		}

		public void setCleanupIntervalMs(long cleanupIntervalMs) {
			this.cleanupIntervalMs = cleanupIntervalMs;
		}

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

	/**
	 * 토스페이먼츠 PG 설정. .env 의 TOSS_CLIENT_KEY, TOSS_SECRET_KEY, TOSS_SECURITY_KEY 로 주입.
	 * 샌드박스 사용 시 test_ck_ / test_sk_ 로 시작하는 키 사용.
	 */
	public static class Toss {
		/** 클라이언트 키: 프론트 토스 결제창/SDK 초기화용. 노출 가능. test_ck_ / live_ck_ */
		private String clientKey = "";
		/** 시크릿 키: 백엔드에서 결제 승인 API 호출 시만 사용. 노출 금지. test_sk_ / live_sk_ */
		private String secretKey = "";
		/** 보안키: 웹훅 서명 검증 등 선택 사항 */
		private String securityKey = "";

		public String getClientKey() {
			return clientKey;
		}

		public void setClientKey(String clientKey) {
			this.clientKey = clientKey != null ? clientKey : "";
		}

		public String getSecretKey() {
			return secretKey;
		}

		public void setSecretKey(String secretKey) {
			this.secretKey = secretKey != null ? secretKey : "";
		}

		public String getSecurityKey() {
			return securityKey;
		}

		public void setSecurityKey(String securityKey) {
			this.securityKey = securityKey != null ? securityKey : "";
		}
	}

	// API Rate Limit 설정
	public static class RateLimitProps {
		private boolean enabled = true;
		/** 윈도우 내 최대 요청 수. 기본 10. */
		private int requestsPerSecond = 10;
		/** 윈도우 크기(초). 기본 1. */
		private int windowSeconds = 1;

		public boolean isEnabled() { return enabled; }
		public void setEnabled(boolean enabled) { this.enabled = enabled; }
		public int getRequestsPerSecond() { return requestsPerSecond; }
		public void setRequestsPerSecond(int requestsPerSecond) { this.requestsPerSecond = requestsPerSecond; }
		public int getWindowSeconds() { return windowSeconds; }
		public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
	}

	public RateLimitProps getRateLimit() { return rateLimit; }
}
