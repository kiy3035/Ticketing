package com.inyoung.ticketing.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RedisCircuitBreakerExecutor 단위 테스트.
 *
 * <p><b>서킷브레이커(Circuit Breaker)란?</b><br>
 * 전기 차단기처럼 동작한다. Redis 장애가 연속 발생하면 회로를 차단(OPEN)해
 * 이후 요청을 Redis로 전달하지 않고 즉시 fallback을 반환한다.<br>
 * 이를 통해 Redis 장애가 앱 전체로 번지는 연쇄 장애(cascading failure)를 방지한다.</p>
 *
 * <p><b>상태 전이</b>
 * <pre>
 *   CLOSED ─(실패율 초과)──→ OPEN ─(대기 시간 경과)──→ HALF_OPEN
 *     ↑                                                    │
 *     └────────────(프로브 성공)──────────────────────────┘
 *                            (프로브 실패 시 다시 OPEN)
 * </pre></p>
 *
 * <p>실제 Resilience4j CircuitBreaker(테스트 전용 설정)를 사용해
 * CLOSED/OPEN/예외 세 가지 분기를 검증한다.
 * Docker·Spring Context 불필요 — 빠른 피드백.</p>
 */
class RedisCircuitBreakerExecutorTest {

	private CircuitBreaker circuitBreaker;
	private RedisCircuitBreakerExecutor executor;

	@BeforeEach
	void setUp() {
		// 테스트 전용 설정 — 운영 설정(sliding-window-size=10)과 무관하게 동작
		// slidingWindowSize(2)      : 최근 2번의 호출로 실패율을 계산 (운영은 10)
		// failureRateThreshold(100) : 2회 중 100%(= 2회) 실패 시 OPEN 전환
		// waitDurationInOpenState   : OPEN 유지 시간 — 단위 테스트에선 강제 전환하므로 실질적 의미 없음
		// permittedNumberOfCallsInHalfOpenState: HALF_OPEN 프로브 허용 횟수
		CircuitBreakerConfig config = CircuitBreakerConfig.custom()
			.slidingWindowSize(2)
			.failureRateThreshold(100)
			.waitDurationInOpenState(Duration.ofSeconds(60))
			.permittedNumberOfCallsInHalfOpenState(1)
			.build();
		circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("test-cb");
		executor = new RedisCircuitBreakerExecutor(circuitBreaker);
	}

	// ── 시나리오 1: 정상 경로 ─────────────────────────────────────────────────────
	/**
	 * [정상 경로] 서킷 CLOSED 상태 → Redis 호출(action)이 정상 실행되어 결과 반환
	 *
	 * 장애 없는 평상시 흐름.
	 * action 람다가 그대로 실행되고 그 반환값이 caller에게 전달된다.
	 * fallback은 호출되지 않는다.
	 */
	@Test
	@DisplayName("회로 CLOSED — action 결과 그대로 반환")
	void execute_returnsActionResult_whenCircuitClosed() {
		// given: 서킷 초기 상태 = CLOSED (setUp에서 생성된 그대로)

		// when: Redis 호출을 흉내낸 action 람다 실행
		String result = executor.execute("test.op", () -> "redis-value", () -> "fallback");

		// then: fallback이 아닌 실제 Redis 결과가 반환되어야 한다
		assertThat(result).isEqualTo("redis-value");
	}

	// ── 시나리오 2: OPEN fast-fail ─────────────────────────────────────────────
	/**
	 * [차단 경로] 서킷 OPEN 상태 → action 미실행(fast-fail), 즉시 fallback 반환
	 *
	 * OPEN 상태에서는 Resilience4j가 CallNotPermittedException을 발생시켜
	 * action 람다 자체를 아예 호출하지 않는다.
	 * 이것이 fast-fail의 핵심: 이미 장애 중인 Redis에 요청을 보내지 않아
	 * 불필요한 timeout 대기와 스레드 풀 소진을 막는다.
	 *
	 * transitionToOpenState(): Resilience4j 공식 API.
	 * 실제 실패 없이도 OPEN으로 강제 전환 — Redis를 실제로 내릴 필요 없이 장애 상황 재현.
	 */
	@Test
	@DisplayName("회로 OPEN — CallNotPermittedException → fallback 반환")
	void execute_returnsFallback_whenCircuitOpen() {
		// given: OPEN 상태로 강제 전환 (Redis 호출 없이 즉시 예외 발생)
		circuitBreaker.transitionToOpenState();

		// when
		String result = executor.execute("test.op", () -> "redis-value", () -> "fallback");

		// then: action은 실행되지 않고 fallback 결과가 반환되어야 한다
		assertThat(result).isEqualTo("fallback");
	}

	// ── 시나리오 3: Redis 예외 처리 ────────────────────────────────────────────
	/**
	 * [예외 처리] action에서 RuntimeException 발생 → 서킷브레이커에 실패 기록, fallback 반환
	 *
	 * Redis 연결 불가, 타임아웃 등 실제 오류 상황 시뮬레이션.
	 * executor는 예외를 잡아 서킷브레이커에 실패로 기록하고 fallback을 반환한다.
	 * 이 실패가 누적되어 slidingWindowSize 내 failureRateThreshold를 초과하면 OPEN 전환.
	 * (이 테스트 설정: 2회 중 2회(100%) 실패 시 OPEN)
	 */
	@Test
	@DisplayName("Redis 예외 발생 — 실패 기록 후 fallback 반환")
	void execute_returnsFallback_whenRedisThrows() {
		// when: action에서 예외를 던지도록 설정 (Redis 장애 모사)
		String result = executor.execute(
			"test.op",
			() -> { throw new RuntimeException("Redis connection refused"); },
			() -> "fallback"
		);

		// then: 예외가 외부로 전파되지 않고 fallback 결과가 반환되어야 한다
		assertThat(result).isEqualTo("fallback");
	}
}
