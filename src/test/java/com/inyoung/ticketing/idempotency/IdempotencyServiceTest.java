package com.inyoung.ticketing.idempotency;

import java.time.Duration;
import java.util.Optional;

import com.inyoung.ticketing.common.idempotency.IdempotencyService;
import com.inyoung.ticketing.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 멱등성 서비스 통합 테스트.
 * Redis를 통해 키 선점, 결과 저장, 결과 조회가 올바르게 동작하는지 검증한다.
 */
class IdempotencyServiceTest extends IntegrationTestBase {

	@Autowired private IdempotencyService idempotencyService;

	@Test
	@DisplayName("멱등성 키 선점 → 결과 저장 → 동일 키 재요청 시 캐시 반환")
	void acquireAndRetrieve() {
		String key = "test-idempotency-" + System.currentTimeMillis();
		Duration ttl = Duration.ofMinutes(1);

		boolean acquired = idempotencyService.acquireKey(key, ttl);
		assertThat(acquired).isTrue();

		boolean duplicate = idempotencyService.acquireKey(key, ttl);
		assertThat(duplicate).as("중복 키는 선점 불가").isFalse();

		assertThat(idempotencyService.isProcessing(key)).isTrue();

		idempotencyService.saveResult(key, "success-result", ttl);
		Optional<Object> cached = idempotencyService.getResult(key, Object.class);
		assertThat(cached).isPresent();
	}

	@Test
	@DisplayName("처리 실패 시 키 해제 → 재시도 가능")
	void releaseOnFailure() {
		String key = "test-idempotency-fail-" + System.currentTimeMillis();
		Duration ttl = Duration.ofMinutes(1);

		idempotencyService.acquireKey(key, ttl);
		idempotencyService.releaseKey(key);

		boolean reacquired = idempotencyService.acquireKey(key, ttl);
		assertThat(reacquired).as("해제 후 재선점 가능").isTrue();
	}
}
