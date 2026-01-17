package com.inyoung.ticketing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ticketing")
public class TicketingProperties {
	private Hold hold = new Hold();

	public Hold getHold() {
		return hold;
	}

	public static class Hold {
		private long ttlSeconds = 300;
		private long cleanupIntervalMs = 60000;

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
	}
}
