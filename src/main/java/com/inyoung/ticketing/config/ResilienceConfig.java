package com.inyoung.ticketing.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Resilience4j CircuitBreaker 설정.
 *
 * <p>Redis 장애 시 서킷이 열려 빠른 실패(fast-fail)로 전환되고,
 * 일정 시간 후 반개방 상태에서 Redis 복구를 감지한다.</p>
 *
 * <p>서킷 상태: CLOSED → OPEN → HALF_OPEN → CLOSED
 * <ul>
 *   <li>CLOSED: 정상. 모든 요청이 Redis로 전달됨</li>
 *   <li>OPEN: 장애. Redis 호출 차단, fallback 실행</li>
 *   <li>HALF_OPEN: 제한된 요청으로 Redis 복구 확인</li>
 * </ul>
 */
@Configuration
public class ResilienceConfig {

	@Bean
	public CircuitBreakerRegistry circuitBreakerRegistry() {
		CircuitBreakerConfig config = CircuitBreakerConfig.custom()
			.slidingWindowSize(10)
			.failureRateThreshold(50)
			.waitDurationInOpenState(Duration.ofSeconds(30))
			.permittedNumberOfCallsInHalfOpenState(3)
			.slowCallDurationThreshold(Duration.ofSeconds(2))
			.slowCallRateThreshold(80)
			.build();
		return CircuitBreakerRegistry.of(config);
	}

	@Bean
	public CircuitBreaker redisCircuitBreaker(CircuitBreakerRegistry registry) {
		return registry.circuitBreaker("redisCircuitBreaker");
	}
}
