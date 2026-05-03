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
 * RedisLockService 단위 테스트 (Unit Test).
 *
 * ══ 이 테스트가 검증하는 것 ══════════════════════════════════════════
 * 좌석 동시 선점 시 사용하는 Redis 분산 락의 핵심 로직:
 *   - tryLock : Redis SET NX(SET if Not eXists) 결과에 따라 토큰 반환/빈값 반환
 *   - unlock  : Lua 스크립트로 "내 토큰일 때만 삭제" 동작
 *
 * ══ 왜 Mock을 쓰는가 ═════════════════════════════════════════════════
 * 이 테스트는 "RedisLockService의 로직이 옳은가"만 검증한다.
 * 실제 Redis 서버 없이도 테스트가 가능하고, 수 밀리초 만에 끝난다.
 * 실제 Redis 동작(동시성 정확성)은 RedisLockConcurrencyTest가 검증한다.
 */
// @ExtendWith(MockitoExtension.class) : JUnit5에 Mockito를 붙여주는 어노테이션.
// 이게 있어야 @Mock, @InjectMocks 등 Mockito 어노테이션이 동작한다.
@ExtendWith(MockitoExtension.class)
class RedisLockServiceTest {

	// @Mock : 실제 구현체 대신 "가짜 객체(Mock)"를 생성한다.
	// 가짜이므로 실제 Redis에 연결하지 않고, 우리가 원하는 값을 반환하도록 프로그래밍할 수 있다.
	@Mock
	private StringRedisTemplate redisTemplate;

	// ValueOperations : redisTemplate.opsForValue()가 반환하는 타입.
	// SET/GET 같은 단순 키-값 명령을 담당한다.
	@Mock
	private ValueOperations<String, String> valueOperations;

	// 테스트 대상 클래스(System Under Test). Mock이 아닌 실제 구현체다.
	private RedisLockService lockService;

	// @BeforeEach : 각 @Test 메서드 실행 직전에 호출된다.
	// 여러 테스트가 공유 상태(lockService)를 깨끗이 시작할 수 있도록 초기화한다.
	@BeforeEach
	void setUp() {
		// Mock된 redisTemplate을 주입해서 실제 RedisLockService 객체를 만든다.
		lockService = new RedisLockService(redisTemplate);
	}

	// ─────────────────────────────────────────────────────────────────────────────
	// tryLock 테스트
	// ─────────────────────────────────────────────────────────────────────────────

	/**
	 * [시나리오] Redis SET NX가 성공했을 때(아무도 락을 안 잡고 있음) → 락 토큰 반환
	 *
	 * setIfAbsent = SET key value NX EX ttl
	 * NX(Not eXists): 키가 없을 때만 SET. 동시에 여러 요청이 와도 딱 하나만 성공.
	 * 이 메서드가 true를 반환하면 → 락 획득 성공 → UUID 토큰을 돌려준다.
	 *
	 * 반환된 토큰은 나중에 unlock() 호출 시 "내가 잡은 락이 맞다"는 증거로 사용된다.
	 */
	@Test
	void tryLock_returnsToken_whenSetIfAbsentSucceeds() {
		// given: Redis SET NX가 성공(true)하도록 Mock 설정
		// when(대상.메서드(인자)).thenReturn(반환값) 형태로 Mock 동작을 정의한다.
		// anyString() : 어떤 문자열이든 매칭 / eq(Duration.ofSeconds(5)) : 정확히 5초짜리 Duration만 매칭
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(5))))
			.thenReturn(Boolean.TRUE); // 락 획득 성공을 흉내낸다

		// when: tryLock 실행
		var result = lockService.tryLock("lock:seat:1", Duration.ofSeconds(5));

		// then: 토큰이 존재하고 비어 있지 않아야 한다
		// isPresent() : Optional에 값이 있는지 확인
		assertThat(result).isPresent();
		// isNotBlank() : 빈 문자열이나 공백만 있는 문자열이 아닌지 확인
		assertThat(result.get()).isNotBlank();
	}

	/**
	 * [시나리오] 다른 사람이 이미 락을 잡아서 SET NX 실패 → Optional.empty() 반환
	 *
	 * setIfAbsent가 false를 반환하면 → 락 획득 실패.
	 * 서비스 레이어에서는 이 빈 값을 보고 HTTP 429 "Seat is busy"를 반환한다.
	 */
	@Test
	void tryLock_returnsEmpty_whenSetIfAbsentFails() {
		// given: Redis SET NX 실패(다른 사람이 이미 잡고 있음)
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
			.thenReturn(Boolean.FALSE); // 락 획득 실패를 흉내낸다

		// when
		var result = lockService.tryLock("lock:seat:1", Duration.ofSeconds(5));

		// then: 토큰이 없어야 한다 (isEmpty = Optional이 비어있음)
		assertThat(result).isEmpty();
	}

	// ─────────────────────────────────────────────────────────────────────────────
	// unlock 테스트
	// ─────────────────────────────────────────────────────────────────────────────

	/**
	 * [시나리오] 내 토큰으로 unlock → 성공(true)
	 *
	 * unlock은 Lua 스크립트로 구현된다.
	 * Lua 스크립트: "키의 값 == 내 토큰"이면 DEL하고 1 반환, 아니면 0 반환.
	 * 이렇게 하면 "남의 락을 실수로 해제"하는 사고를 막을 수 있다.
	 *
	 * Mock은 Lua 스크립트 실행(execute)이 1L을 반환하도록 설정 → unlock은 true.
	 */
	@Test
	void unlock_returnsTrue_whenTokenMatches() {
		// given: Lua 스크립트 실행 결과가 1L(성공)이도록 Mock 설정
		// Collections.singletonList : Redis 명령에 넘길 키 목록
		when(redisTemplate.execute(any(RedisScript.class),
			eq(Collections.singletonList("lock:seat:1")),
			eq("token-123")))
			.thenReturn(1L); // 1 = 토큰 일치, 락 해제 성공

		// when
		boolean result = lockService.unlock("lock:seat:1", "token-123");

		// then
		assertThat(result).isTrue();
	}

	/**
	 * [시나리오] 잘못된 토큰으로 unlock 시도 → 실패(false)
	 *
	 * 만료 후 다른 사람이 같은 키로 새 락을 잡은 경우, 또는 토큰을 잘못 전달한 경우.
	 * Lua 스크립트가 토큰 불일치를 감지하고 DEL하지 않고 0을 반환.
	 * 이 안전장치 덕분에 다른 사람의 락이 실수로 해제되지 않는다.
	 */
	@Test
	void unlock_returnsFalse_whenTokenDoesNotMatch() {
		// given: Lua 스크립트가 0L(실패, 토큰 불일치)을 반환하도록 설정
		when(redisTemplate.execute(any(RedisScript.class),
			eq(Collections.singletonList("lock:seat:1")),
			eq("wrong-token")))
			.thenReturn(0L); // 0 = 토큰 불일치, 락 해제 거부

		// when
		boolean result = lockService.unlock("lock:seat:1", "wrong-token");

		// then
		assertThat(result).isFalse();
	}
}
