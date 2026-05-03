package com.inyoung.ticketing.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.function.Supplier;
import com.inyoung.ticketing.common.resilience.RedisCircuitBreakerExecutor;
import com.inyoung.ticketing.config.TicketingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

/**
 * QueueService 단위 테스트 (Unit Test).
 *
 * ══ 이 테스트가 검증하는 것 ══════════════════════════════════════════
 * Redis ZSet(Sorted Set) 기반 대기열의 핵심 로직:
 *   - enterQueue    : 대기열 진입 후 토큰·순번·대기 인원 반환
 *   - getRank       : Redis ZSet의 0-based rank를 1-based로 변환
 *   - countWaiting  : 현재 대기 인원 수 조회
 *
 * ══ ZSet이란 ═════════════════════════════════════════════════════════
 * Sorted Set: 각 멤버에 score(점수)가 붙어 자동 정렬된다.
 * 여기서는 score에 입장 시각(epoch)을 넣어 먼저 온 사람이 앞 순번을 갖는다.
 * rank(0-based): ZSet에서 순서. 0이 가장 앞. → 사용자에게 보여줄 때는 +1해서 1-based로.
 */
@ExtendWith(MockitoExtension.class)
class QueueServiceTest {

	@Mock
	private StringRedisTemplate redisTemplate;

	// ZSetOperations : redisTemplate.opsForZSet()가 반환하는 타입.
	// ZADD, ZRANK, ZCARD 같은 Sorted Set 명령을 담당한다.
	@Mock
	private ValueOperations<String, String> valueOperations;
	@Mock
	private ZSetOperations<String, String> zSetOperations;

	// RedisCircuitBreakerExecutor : Redis 호출을 서킷브레이커로 감싸는 실행기.
	// 단위 테스트에서는 "서킷브레이커 로직은 건너뛰고 action만 그대로 실행"하도록 Mock 처리한다.
	@Mock
	private RedisCircuitBreakerExecutor redisCb;

	private TicketingProperties properties;
	private QueueService queueService;

	private static final Long CONCERT_ID = 1L;
	private static final String USER_ID = "user1";

	@BeforeEach
	void setUp() {
		// redisCb.execute(operation, action, fallback) 호출 시
		// action 람다(1번 인덱스)를 그대로 실행해서 결과를 돌려주도록 설정한다.
		// 이렇게 해야 내부의 redisTemplate 호출(ZSet 명령)이 실제로 실행되어
		// 아래에서 설정한 Mock stub이 동작한다.
		when(redisCb.execute(anyString(), any(), any()))
			.thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());

		when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

		// TicketingProperties : application.properties 값을 담는 설정 클래스.
		// 테스트에서는 직접 값을 세팅해서 properties 파일 없이도 동작하게 한다.
		properties = new TicketingProperties();
		properties.getQueue().setTokenTtlSeconds(60);
		queueService = new QueueService(redisTemplate, properties, redisCb);
	}

	/**
	 * [시나리오] 처음 대기열에 진입하는 사용자
	 *
	 * 기대 동작:
	 *   - 새 토큰이 발급된다 (UUID 형태, 비어있지 않음)
	 *   - rank = 1 (1번째 대기자, 1-based 변환)
	 *   - totalWaiting = 1 (현재 총 대기자 수)
	 *
	 * Mock 설정 이유:
	 *   - zSetOperations.rank()가 0L을 반환 → 서비스에서 +1 → 최종 rank = 1
	 *   - zSetOperations.size()가 1L을 반환 → totalWaiting = 1
	 */
	@Test
	void enterQueue_returnsTokenWithRankAndTotalWaiting() {
		// given
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		// range() : 이미 대기 중인 토큰 목록 조회. 빈 Set = 아무도 없음
		when(zSetOperations.range(eq("queue:concert:" + CONCERT_ID), eq(0L), eq(-1L)))
			.thenReturn(Collections.emptySet());
		// add() : ZADD 명령. 대기열에 토큰을 추가. true = 성공
		when(zSetOperations.add(anyString(), anyString(), anyDouble())).thenReturn(true);
		// rank() : 0-based 순위 반환. 0 = 가장 앞 → 서비스에서 +1 → 1번째
		when(zSetOperations.rank(anyString(), anyString())).thenReturn(0L);
		// size() : 대기열 총 인원 수
		when(zSetOperations.size(eq("queue:concert:" + CONCERT_ID))).thenReturn(1L);

		// when
		QueueService.QueueTokenInfo result = queueService.enterQueue(CONCERT_ID, USER_ID);

		// then
		assertThat(result).isNotNull();
		assertThat(result.getToken()).isNotBlank();          // 토큰이 발급됐는지
		assertThat(result.getRank()).isEqualTo(1L);          // 1번째 대기자인지
		assertThat(result.getTotalWaiting()).isEqualTo(1L);  // 총 대기자 1명인지
	}

	/**
	 * [시나리오] ZSet.rank()는 0-based → 서비스는 1-based로 변환해서 반환
	 *
	 * Redis ZRANK 명령은 0부터 시작한다 (첫 번째 = 0, 두 번째 = 1 ...).
	 * 사용자에게는 "당신은 3번째입니다"처럼 1부터 시작하는 숫자를 보여줘야 한다.
	 * rank() = 2(0-based) → +1 → 반환값 = 3(1-based)
	 */
	@Test
	void getRank_returnsOneBasedRank() {
		// given: Redis가 0-based rank 2를 반환
		when(zSetOperations.rank(eq("queue:concert:" + CONCERT_ID), eq("token-1")))
			.thenReturn(2L);

		// when
		Long rank = queueService.getRank(CONCERT_ID, "token-1");

		// then: 1-based로 변환된 3이어야 한다
		assertThat(rank).isEqualTo(3L);
	}

	/**
	 * [시나리오] 대기열에 없는 토큰 조회 → null 반환
	 *
	 * 만료되거나 이미 입장한 토큰, 또는 잘못된 토큰으로 순위를 조회하면
	 * Redis ZRANK가 null을 반환한다. 서비스도 그대로 null을 전달한다.
	 * API에서는 이 null을 보고 "유효하지 않은 토큰" 응답을 반환한다.
	 */
	@Test
	void getRank_returnsNull_whenTokenNotInQueue() {
		// given: Redis가 null 반환 (토큰이 ZSet에 없음)
		when(zSetOperations.rank(anyString(), anyString())).thenReturn(null);

		// when
		Long rank = queueService.getRank(CONCERT_ID, "unknown-token");

		// then
		assertThat(rank).isNull();
	}

	/**
	 * [시나리오] 대기열이 비어있을 때 → 0 반환
	 *
	 * ZCARD 명령(ZSet의 크기)이 0을 반환하면 대기자가 없는 상태.
	 * 이 값으로 "대기열 필요 여부" 판단이나 UI에서 "현재 대기자 0명" 표시에 사용한다.
	 */
	@Test
	void countWaiting_returnsZero_whenQueueEmpty() {
		// given: Redis ZCARD = 0 (아무도 없음)
		when(zSetOperations.size(eq("queue:concert:" + CONCERT_ID))).thenReturn(0L);

		// when
		Long count = queueService.countWaiting(CONCERT_ID);

		// then
		assertThat(count).isEqualTo(0L);
	}

	/**
	 * [시나리오] 대기열에 100명이 있을 때 → 100 반환
	 *
	 * application.properties의 임계값(activation-threshold)과 비교해
	 * 대기열 활성화 여부를 결정하는 데 사용되는 값이다.
	 */
	@Test
	void countWaiting_returnsSize_whenQueueHasMembers() {
		// given: Redis ZCARD = 100
		when(zSetOperations.size(eq("queue:concert:" + CONCERT_ID))).thenReturn(100L);

		// when
		Long count = queueService.countWaiting(CONCERT_ID);

		// then
		assertThat(count).isEqualTo(100L);
	}
}
