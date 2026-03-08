package com.inyoung.ticketing.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * RedisLockService 단위 테스트.
 * 좌석 동시 선점 시 사용하는 Redis 기반 분산 락의 "획득(tryLock)"과 "해제(unlock)" 동작을 검증한다.
 * Redis는 Mock으로 대체하여 실제 Redis 없이 빠르게 실행한다.
 */
@ExtendWith(MockitoExtension.class)
class RedisLockServiceTest {

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	private RedisLockService lockService;

	@BeforeEach
	void setUp() {
		lockService = new RedisLockService(redisTemplate);
	}

	/**
	 * tryLock: Redis SET key value NX EX ttl 이 성공했을 때
	 * - 반환값이 Optional.of(토큰) 형태로 존재하는지
	 * - 토큰이 비어 있지 않은(UUID) 문자열인지
	 * 검증. 락을 잡은 쪽만 이 토큰으로 나중에 unlock 할 수 있도록 하는 것이 목적.
	 */
	@Test
	void tryLock_returnsToken_whenSetIfAbsentSucceeds() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(5))))
			.thenReturn(Boolean.TRUE);

		var result = lockService.tryLock("lock:seat:1", Duration.ofSeconds(5));

		assertThat(result).isPresent();
		assertThat(result.get()).isNotBlank();
	}

	/**
	 * tryLock: 이미 다른 클라이언트가 락을 잡아서 SET NX 가 실패했을 때
	 * - 반환값이 Optional.empty() 인지 검증.
	 * 이 경우 호출자는 429 "Seat is busy" 등을 반환해야 하므로, 빈 값 여부가 중요하다.
	 */
	@Test
	void tryLock_returnsEmpty_whenSetIfAbsentFails() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
			.thenReturn(Boolean.FALSE);

		var result = lockService.tryLock("lock:seat:1", Duration.ofSeconds(5));

		assertThat(result).isEmpty();
	}

	/**
	 * unlock: Lua 스크립트로 "키의 값 == 내 토큰"일 때만 DEL 하므로,
	 * 토큰이 일치하면 Redis가 1을 반환하고 unlock 결과는 true.
	 * 본인이 잡은 락만 안전하게 해제되는지 검증한다.
	 */
	@Test
	void unlock_returnsTrue_whenTokenMatches() {
		when(redisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList("lock:seat:1")), eq("token-123")))
			.thenReturn(1L);

		boolean result = lockService.unlock("lock:seat:1", "token-123");

		assertThat(result).isTrue();
	}

	/**
	 * unlock: 다른 사람의 토큰이나 만료 후 재진입한 경우처럼 토큰이 불일치하면
	 * Lua 스크립트가 DEL 하지 않고 0을 반환. unlock 결과는 false.
	 * 다른 사람의 락을 해제하지 않도록 하는 안전장치를 검증한다.
	 */
	@Test
	void unlock_returnsFalse_whenTokenDoesNotMatch() {
		when(redisTemplate.execute(any(RedisScript.class), eq(Collections.singletonList("lock:seat:1")), eq("wrong-token")))
			.thenReturn(0L);

		boolean result = lockService.unlock("lock:seat:1", "wrong-token");

		assertThat(result).isFalse();
	}
}
