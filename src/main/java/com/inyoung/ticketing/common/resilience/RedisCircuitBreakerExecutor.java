package com.inyoung.ticketing.common.resilience;

import java.util.function.Supplier;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ══════════════════════════════════════════════════════
 * Redis 호출을 서킷브레이커로 감싸주는 실행기(Executor).
 * ══════════════════════════════════════════════════════
 *
 * 왜 이 클래스가 필요한가?
 * ──────────────────────────────────────────────────────
 * Redis를 직접 호출하는 곳(HoldStore, QueueService 등)이 여러 군데다.
 * 각 호출마다 try-catch + 서킷브레이커 코드를 반복 작성하면 중복이 심해진다.
 * 이 클래스가 그 공통 패턴을 한 곳에 모아 두고,
 * 호출하는 쪽은 "무엇을 실행할지(action)"와 "실패 시 무엇을 돌려줄지(fallback)"만 넘기면 된다.
 *
 * 사용 예시:
 * ──────────────────────────────────────────────────────
 *   redisCb.execute(
 *       "hold.getHold",                        // 로그에 찍힐 작업 이름
 *       () -> redisTemplate.get(tokenKey),     // 실제 Redis 호출 (action)
 *       () -> null                             // Redis가 죽었을 때 대신 반환할 값 (fallback)
 *   );
 *
 * fallback 전략 선택 기준:
 * ──────────────────────────────────────────────────────
 *   - 홀드 생성 → 실패 시 0L(생성 안 됨 처리)
 *   - 홀드 조회 → 실패 시 null(홀드 없음 처리)
 *   - 좌석 홀드 여부 → 실패 시 null(홀드 없는 것처럼)
 *   → Redis가 죽어도 "데이터 없음"으로 처리해 요청이 완전히 실패하지 않게 한다.
 *     단, 중요한 비즈니스 동작(락 획득 등)은 fallback에서 실패로 처리한다.
 */
@Component
public class RedisCircuitBreakerExecutor {
	private static final Logger log = LoggerFactory.getLogger(RedisCircuitBreakerExecutor.class);

	// ResilienceConfig에서 @Bean으로 등록한 서킷브레이커 인스턴스.
	// 실제 상태(CLOSED/OPEN/HALF_OPEN)를 추적하고 실패 카운트를 관리한다.
	private final CircuitBreaker redisCircuitBreaker;

	public RedisCircuitBreakerExecutor(CircuitBreaker redisCircuitBreaker) {
		this.redisCircuitBreaker = redisCircuitBreaker;
	}

	/**
	 * Redis 호출(action)을 서킷브레이커로 감싸 실행한다.
	 * 실패 시 fallback 결과를 반환하고, 실패 횟수를 서킷브레이커에 기록한다.
	 *
	 * @param operation 로그에 출력할 작업 이름 (예: "hold.getHold"). 어디서 실패했는지 추적용.
	 * @param action    실제 Redis를 호출하는 람다. 예: () -> redisTemplate.get(key)
	 * @param fallback  Redis 호출이 실패했을 때 대신 반환할 값을 만드는 람다.
	 * @param <T>       반환 타입 (Long, String, Boolean 등 호출하는 곳에서 결정됨)
	 * @return action 결과 또는 fallback 결과
	 */
	public <T> T execute(String operation, Supplier<T> action, Supplier<T> fallback) {
		try {
			// 서킷브레이커를 통해 action을 실행한다.
			// 내부적으로 실행 시간을 측정하고, 성공/실패/슬로우콜 여부를 기록한다.
			return redisCircuitBreaker.executeSupplier(action);

		} catch (CallNotPermittedException openState) {
			// 서킷브레이커가 OPEN 상태일 때 발생하는 예외.
			// Redis를 호출조차 하지 않고 즉시 이 예외가 던져진다.
			// → 이미 Redis가 죽은 것으로 판단했으므로 타임아웃을 기다리지 않고 바로 fallback 반환.
			log.warn("Redis circuit open - fallback. op={}", operation);
			return fallback.get();

		} catch (Exception ex) {
			// Redis 호출 자체가 실패한 경우 (연결 오류, 타임아웃 등).
			// 이 실패는 서킷브레이커의 슬라이딩 윈도우에 기록되어
			// 실패율이 임계치(50%)를 넘으면 서킷이 OPEN으로 전환된다.
			log.warn("Redis call failed - fallback. op={}, reason={}", operation, ex.getMessage());
			return fallback.get();
		}
	}
}
