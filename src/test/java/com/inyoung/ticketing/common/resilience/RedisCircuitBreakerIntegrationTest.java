package com.inyoung.ticketing.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import com.inyoung.ticketing.support.IntegrationTestBase;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * RedisCircuitBreakerExecutor 통합 테스트.
 *
 * <p>Spring Context의 실제 redisCircuitBreaker 빈(운영 설정 그대로)을 사용해
 * CLOSED→OPEN→HALF_OPEN→CLOSED 전이와 fast-fail 동작을 검증한다.</p>
 *
 * <p>Resilience4j가 공식 지원하는 {@code transitionToXxxState()} API로
 * 상태를 프로그래밍으로 제어 — Redis를 실제로 내렸다 올릴 필요 없음.</p>
 */
class RedisCircuitBreakerIntegrationTest extends IntegrationTestBase {

	@Autowired
	private CircuitBreaker redisCircuitBreaker;

	@Autowired
	private RedisCircuitBreakerExecutor redisCircuitBreakerExecutor;

	@BeforeEach
	void resetCircuit() {
		// 테스트 간 상태 오염 방지 — 매 테스트 전 CLOSED 로 초기화
		redisCircuitBreaker.transitionToClosedState();
	}

	// ── 시나리오 1: OPEN fast-fail + 미호출 검증 ────────────────────────────────
	/**
	 * [fast-fail 검증] OPEN 상태에서 action이 실제로 호출되지 않는지 확인
	 *
	 * 단위 테스트(RedisCircuitBreakerExecutorTest)와 달리 운영 빈(Spring Context)을 사용해
	 * 실제 Resilience4j 설정값(application.yml의 resilience4j.*)이 올바르게 적용됐는지 함께 검증한다.
	 *
	 * AtomicInteger로 action 호출 횟수를 추적해 fast-fail임을 명확히 증명한다.
	 * action이 0회 호출 = Redis에 요청을 전혀 보내지 않음 = timeout 없이 즉시 응답.
	 */
	@Test
	@DisplayName("OPEN 강제 전환 → action 미실행(fast-fail), fallback 반환")
	void execute_returnsFallback_withoutCallingAction_whenCircuitOpen() {
		// given: 서킷을 강제로 OPEN (운영 빈에도 transitionToOpenState()가 동작함을 확인)
		redisCircuitBreaker.transitionToOpenState();
		// action 호출 횟수 추적용 — AtomicInteger는 람다 내부에서 수정 가능 (effectively final 우회)
		AtomicInteger actionCallCount = new AtomicInteger(0);

		// when
		String result = redisCircuitBreakerExecutor.execute(
			"test.forced.open",
			() -> { actionCallCount.incrementAndGet(); return "redis-value"; },
			() -> "fallback"
		);

		// then: Redis를 아예 호출하지 않고 즉시 fallback 반환
		assertThat(result).isEqualTo("fallback");
		assertThat(actionCallCount.get())
			.as("OPEN 상태에서는 Redis를 호출하지 않아야 한다 (fast-fail)")
			.isEqualTo(0);
	}

	// ── 시나리오 2: 실패 누적 → 자동 OPEN 전환 ──────────────────────────────────
	/**
	 * [자동 OPEN 전환] 슬라이딩 윈도우 내 실패율 초과 → 서킷 자동 차단
	 *
	 * 운영 설정: sliding-window-size=10, failure-rate-threshold=50%
	 * → 10회 중 6회 실패(60%) 시 OPEN 전환
	 *
	 * 슬라이딩 윈도우: 최근 N회 호출을 추적. 10회가 쌓인 후부터 실패율을 계산한다.
	 * 즉 9회 연속 실패해도 윈도우가 안 찼으면 OPEN이 안 된다.
	 * 10회를 채운 시점에 60% 이상이면 즉시 OPEN — 이것이 이 테스트의 핵심 검증 포인트.
	 */
	@Test
	@DisplayName("슬라이딩 윈도우 실패율 초과 → OPEN 전환")
	void circuitBreaker_transitionsToOpen_afterFailureRateExceeded() {
		// 초기 상태 = CLOSED 임을 명시적으로 확인 (beforeEach에서 보장되지만 문서화 목적)
		assertThat(redisCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

		// 10회 호출: 앞 6회는 실패, 뒤 4회는 성공 → 실패율 60% → OPEN 조건 충족
		for (int i = 0; i < 10; i++) {
			final int callNum = i;
			redisCircuitBreakerExecutor.execute(
				"test.failure",
				() -> {
					// callNum 0~5: 예외 발생 (실패 기록), callNum 6~9: 정상 반환 (성공 기록)
					if (callNum < 6) throw new RuntimeException("simulated Redis failure");
					return "ok";
				},
				() -> "fallback"
			);
		}

		// 10회 중 6회 실패(60%) → threshold(50%) 초과 → OPEN 상태로 전환되어야 한다
		assertThat(redisCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
	}

	// ── 시나리오 3: HALF_OPEN → 프로브 성공 → CLOSED 복귀 ────────────────────────
	/**
	 * [자동 복구] HALF_OPEN 상태에서 프로브 성공 시 CLOSED 복귀
	 *
	 * HALF_OPEN: 서킷이 OPEN된 후 일정 시간(waitDurationInOpenState)이 지나면
	 * 자동으로 진입하는 "시범 운영" 상태. 제한된 횟수만큼 실제 요청을 허용해
	 * Redis가 회복됐는지 확인(프로브)한다.
	 *
	 * 운영 설정: permitted-number-of-calls-in-half-open-state=3
	 * → 3회 프로브가 모두 성공하면 CLOSED 복귀 (서비스 정상 재개)
	 * → 1회라도 실패하면 다시 OPEN (장애 지속 판단)
	 *
	 * 테스트에서는 waitDuration을 기다리는 대신 transitionToHalfOpenState()로 즉시 전환한다.
	 */
	@Test
	@DisplayName("HALF_OPEN → 프로브 3회 성공 → CLOSED 복귀")
	void circuitBreaker_halfOpen_closesAfterSuccessfulProbes() {
		// given: OPEN → HALF_OPEN 으로 강제 전환 (waitDurationInOpenState 대기 생략)
		redisCircuitBreaker.transitionToOpenState();
		redisCircuitBreaker.transitionToHalfOpenState();
		assertThat(redisCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

		// when: 운영 설정의 permitted-number-of-calls-in-half-open-state(=3) 만큼 성공 프로브
		for (int i = 0; i < 3; i++) {
			// 성공 프로브: 예외 없이 "ok" 반환 → 서킷브레이커에 성공으로 기록
			redisCircuitBreakerExecutor.execute("test.probe", () -> "ok", () -> "fallback");
		}

		// then: 3회 모두 성공 → CLOSED 복귀 (Redis 정상 복구 판단, 서비스 재개)
		assertThat(redisCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
	}
}
