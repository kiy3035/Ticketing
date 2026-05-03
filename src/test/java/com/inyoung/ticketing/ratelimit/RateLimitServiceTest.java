package com.inyoung.ticketing.ratelimit;

import com.inyoung.ticketing.common.ratelimit.RateLimitService;
import com.inyoung.ticketing.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis Sliding Window Rate Limiter 통합 테스트.
 *
 * <p><b>슬라이딩 윈도우 알고리즘 개요</b>:
 * 고정 윈도우(Fixed Window) 방식은 윈도우 경계 직전·직후에 요청이 몰리면
 * 실제로 2배의 요청이 통과하는 "경계 버스트" 문제가 있다.
 * 슬라이딩 윈도우는 "현재 시각 기준으로 windowSeconds 이내의 요청 수"를
 * Redis Sorted Set(ZADD·ZREMRANGEBYSCORE·ZCARD)으로 계산하므로
 * 어느 시점을 기준으로 잡아도 maxRequests 를 초과하지 않는다.</p>
 *
 * <p>실제 Redis Testcontainers 환경에서 ZADD/ZCARD 동작을 검증한다.</p>
 */
class RateLimitServiceTest extends IntegrationTestBase {

	@Autowired private RateLimitService rateLimitService;

	/**
	 * 슬라이딩 윈도우 기본 동작 검증.
	 *
	 * <p>시나리오:
	 * <ol>
	 *   <li>maxRequests=3, windowSeconds=1 로 설정</li>
	 *   <li>같은 identifier 로 3번 요청 → 모두 허용(true)</li>
	 *   <li>4번째 요청 → 한도 초과로 거부(false)</li>
	 * </ol>
	 *
	 * <p><b>identifier 에 타임스탬프를 붙이는 이유</b>: Testcontainers 는 클래스 간에
	 * Redis 컨테이너를 공유한다({@link com.inyoung.ticketing.support.IntegrationTestBase}).
	 * 고정 문자열을 사용하면 다른 테스트가 앞서 실행되어 카운트가 남아있을 때
	 * 이 테스트가 wrongful false 를 받을 수 있다. 타임스탬프로 유니크 key 를 생성해
	 * 테스트 간 간섭을 차단한다.</p>
	 */
	@Test
	@DisplayName("요청 한도 초과 시 거부")
	void rateLimitExceeded() {
		// 타임스탬프를 붙여 다른 테스트와 Redis 키 충돌 방지
		String identifier = "test-user-" + System.currentTimeMillis();
		int maxRequests = 3;
		int windowSeconds = 1;

		// 한도 내 요청 → 모두 허용
		for (int i = 0; i < maxRequests; i++) {
			boolean allowed = rateLimitService.isAllowed(identifier, maxRequests, windowSeconds);
			assertThat(allowed).as("요청 %d는 허용되어야 함", i + 1).isTrue();
		}

		// maxRequests+1 번째 요청 → 슬라이딩 윈도우 내 카운트 초과로 거부
		boolean exceeded = rateLimitService.isAllowed(identifier, maxRequests, windowSeconds);
		assertThat(exceeded).as("한도 초과 시 거부").isFalse();
	}
}
