package com.inyoung.ticketing.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ══════════════════════════════════════════════════════
 * 서킷브레이커(Circuit Breaker) 란?
 * ══════════════════════════════════════════════════════
 *
 * 이름 그대로 전기 회로 차단기(두꺼비집)에서 따온 개념이다.
 * 전기 과부하가 생기면 두꺼비집이 차단해 전체 화재를 막듯,
 * Redis 서버 장애가 생기면 서킷브레이커가 Redis 호출을 차단해
 * 앱 전체가 타임아웃으로 멈추는 것을 막는다.
 *
 * ──────────────────────────────────────────────────────
 * 상태 전이 (3가지 상태)
 * ──────────────────────────────────────────────────────
 *
 *  [CLOSED] ──실패율 50% 초과──▶ [OPEN] ──30초 대기──▶ [HALF_OPEN]
 *     ▲                                                      │
 *     └──────────── 복구 확인 성공(3회 시험 통과) ──────────────┘
 *     └──────────── 복구 확인 실패 ──────────────────▶ [OPEN] 재진입
 *
 *  CLOSED   : 정상 상태. Redis 호출이 모두 통과된다.
 *  OPEN     : 장애 감지. Redis를 아예 호출하지 않고 즉시 fallback을 반환한다.
 *             → 타임아웃 2초를 기다리지 않고 바로 응답하므로 사용자 영향 최소화.
 *  HALF_OPEN: 30초 후 Redis가 살아났는지 소량(3회)의 요청으로 확인하는 상태.
 *
 * ──────────────────────────────────────────────────────
 * 설정값은 어디서 관리하나?
 * ──────────────────────────────────────────────────────
 *
 * application.properties의 아래 항목들이 실제 설정이다.
 *   resilience4j.circuitbreaker.instances.redisCircuitBreaker.*
 *
 * 이 Java 파일에서 CircuitBreakerRegistry 빈을 직접 @Bean으로 선언하면
 * Spring Boot가 "이미 Registry 빈이 있네?" 하고 자동 설정(auto-config)을 건너뛴다.
 * 그러면 application.properties 설정이 아무 효과가 없게 된다.
 *
 * 따라서 여기서는 Registry를 직접 만들지 않고,
 * Spring Boot가 자동으로 만들어 준 Registry를 주입받아 사용한다.
 */
@Configuration
public class ResilienceConfig {

	/**
	 * Redis 전용 CircuitBreaker 빈 생성.
	 *
	 * @param registry Spring Boot auto-config가 application.properties를 읽어 자동 생성한 Registry.
	 *                 여기서 Registry 빈을 선언하지 않아야 이 주입이 auto-config Registry를 가리킨다.
	 * @return "redisCircuitBreaker"라는 이름으로 등록된 서킷브레이커 인스턴스.
	 *         application.properties의 resilience4j.circuitbreaker.instances.redisCircuitBreaker.*
	 *         설정이 이 인스턴스에 적용된다.
	 */
	@Bean
	public CircuitBreaker redisCircuitBreaker(CircuitBreakerRegistry registry) {
		// "redisCircuitBreaker"는 application.properties에서 설정한 인스턴스 이름과 반드시 일치해야 한다.
		return registry.circuitBreaker("redisCircuitBreaker");
	}
}
