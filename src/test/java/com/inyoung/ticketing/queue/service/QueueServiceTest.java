package com.inyoung.ticketing.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
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
 * QueueService 단위 테스트.
 * 콘서트별 대기열 진입(enterQueue), 순번 조회(getRank), 대기 인원 수(countWaiting) 등
 * Redis ZSet 기반 대기열 로직을 검증한다. Redis는 Mock으로 대체한다.
 */
@ExtendWith(MockitoExtension.class)
class QueueServiceTest {

	@Mock
	private StringRedisTemplate redisTemplate;
	@Mock
	private ValueOperations<String, String> valueOperations;
	@Mock
	private ZSetOperations<String, String> zSetOperations;

	private TicketingProperties properties;
	private QueueService queueService;

	private static final Long CONCERT_ID = 1L;
	private static final String USER_ID = "user1";

	@BeforeEach
	void setUp() {
		when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
		properties = new TicketingProperties();
		properties.getQueue().setTokenTtlSeconds(60);
		queueService = new QueueService(redisTemplate, properties);
	}

	/**
	 * enterQueue: 대기열 진입 시
	 * - 새 토큰이 발급되고(비어 있지 않은 문자열)
	 * - 순번(rank)이 1-based로 1, 전체 대기 인원(totalWaiting)이 1로 반환되는지
	 * 검증. 진입 직후 "1번째 대기 중, 총 1명" 같은 UX를 보장한다.
	 */
	@Test
	void enterQueue_returnsTokenWithRankAndTotalWaiting() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(zSetOperations.range(eq("queue:concert:" + CONCERT_ID), eq(0L), eq(-1L)))
			.thenReturn(Collections.emptySet());
		when(zSetOperations.rank(anyString(), anyString())).thenReturn(0L);
		when(zSetOperations.size(eq("queue:concert:" + CONCERT_ID))).thenReturn(1L);

		QueueService.QueueTokenInfo result = queueService.enterQueue(CONCERT_ID, USER_ID);

		assertThat(result).isNotNull();
		assertThat(result.getToken()).isNotBlank();
		assertThat(result.getRank()).isEqualTo(1L);
		assertThat(result.getTotalWaiting()).isEqualTo(1L);
	}

	/**
	 * getRank: Redis ZSet.rank()는 0-based이므로, 서비스에서는 +1 해서 1-based 순번을 반환한다.
	 * rank가 2(0-based)일 때 반환값이 3(1-based)인지 검증.
	 */
	@Test
	void getRank_returnsOneBasedRank() {
		when(zSetOperations.rank(eq("queue:concert:" + CONCERT_ID), eq("token-1")))
			.thenReturn(2L);

		Long rank = queueService.getRank(CONCERT_ID, "token-1");

		assertThat(rank).isEqualTo(3L);
	}

	/**
	 * getRank: 토큰이 해당 콘서트 대기열에 없을 때(만료·이미 나감·잘못된 토큰)
	 * - null이 반환되는지 검증. API에서 404 또는 "유효하지 않은 토큰" 처리에 사용한다.
	 */
	@Test
	void getRank_returnsNull_whenTokenNotInQueue() {
		when(zSetOperations.rank(anyString(), anyString())).thenReturn(null);

		Long rank = queueService.getRank(CONCERT_ID, "unknown-token");

		assertThat(rank).isNull();
	}

	/**
	 * countWaiting: 해당 콘서트 대기열에 아무도 없을 때
	 * - 0이 반환되는지 검증. "대기열 필요 여부" 판단이나 대기 인원 표시에 사용한다.
	 */
	@Test
	void countWaiting_returnsZero_whenQueueEmpty() {
		when(zSetOperations.size(eq("queue:concert:" + CONCERT_ID))).thenReturn(0L);

		Long count = queueService.countWaiting(CONCERT_ID);

		assertThat(count).isEqualTo(0L);
	}

	/**
	 * countWaiting: 대기열 ZSet 크기가 N일 때
	 * - N이 그대로 반환되는지 검증. 대기 인원 수 노출 및 임계값(activation-threshold) 비교에 사용한다.
	 */
	@Test
	void countWaiting_returnsSize_whenQueueHasMembers() {
		when(zSetOperations.size(eq("queue:concert:" + CONCERT_ID))).thenReturn(100L);

		Long count = queueService.countWaiting(CONCERT_ID);

		assertThat(count).isEqualTo(100L);
	}
}
