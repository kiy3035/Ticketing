package com.inyoung.ticketing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// application.properties의 ticketing.* 설정 바인딩용 클래스
@ConfigurationProperties(prefix = "ticketing")
public class TicketingProperties {
	private Hold hold = new Hold();

	// 홀드 관련 설정 접근자
	public Hold getHold() {
		return hold;
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
}
