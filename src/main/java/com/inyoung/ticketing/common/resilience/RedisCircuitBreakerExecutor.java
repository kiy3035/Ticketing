package com.inyoung.ticketing.common.resilience;

import java.util.function.Supplier;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Redis 호출에 CircuitBreaker를 일관 적용하기 위한 실행기.
 *
 * <p>설계 의도:
 * <ul>
 *   <li>Redis 장애/타임아웃이 누적되면 OPEN 상태에서 즉시 차단(fast-fail)</li>
 *   <li>각 호출부가 안전한 기본값(fallback)을 명시적으로 선택</li>
 *   <li>Queue/Hold 같은 핵심 경로에서 같은 패턴을 재사용</li>
 * </ul>
 */
@Component
public class RedisCircuitBreakerExecutor {
	private static final Logger log = LoggerFactory.getLogger(RedisCircuitBreakerExecutor.class);

	private final CircuitBreaker redisCircuitBreaker;

	public RedisCircuitBreakerExecutor(CircuitBreaker redisCircuitBreaker) {
		this.redisCircuitBreaker = redisCircuitBreaker;
	}

	public <T> T execute(String operation, Supplier<T> action, Supplier<T> fallback) {
		try {
			return redisCircuitBreaker.executeSupplier(action);
		} catch (CallNotPermittedException openState) {
			log.warn("Redis circuit open - fallback. op={}", operation);
			return fallback.get();
		} catch (Exception ex) {
			log.warn("Redis call failed - fallback. op={}, reason={}", operation, ex.getMessage());
			return fallback.get();
		}
	}
}
