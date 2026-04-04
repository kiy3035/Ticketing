package com.inyoung.ticketing.ratelimit;

import com.inyoung.ticketing.common.ratelimit.RateLimitService;
import com.inyoung.ticketing.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis Sliding Window Rate Limiter 통합 테스트.
 */
class RateLimitServiceTest extends IntegrationTestBase {

	@Autowired private RateLimitService rateLimitService;

	@Test
	@DisplayName("요청 한도 초과 시 거부")
	void rateLimitExceeded() {
		String identifier = "test-user-" + System.currentTimeMillis();
		int maxRequests = 3;
		int windowSeconds = 1;

		for (int i = 0; i < maxRequests; i++) {
			boolean allowed = rateLimitService.isAllowed(identifier, maxRequests, windowSeconds);
			assertThat(allowed).as("요청 %d는 허용되어야 함", i + 1).isTrue();
		}

		boolean exceeded = rateLimitService.isAllowed(identifier, maxRequests, windowSeconds);
		assertThat(exceeded).as("한도 초과 시 거부").isFalse();
	}
}
