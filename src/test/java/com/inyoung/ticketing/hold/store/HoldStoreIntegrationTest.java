package com.inyoung.ticketing.hold.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import com.inyoung.ticketing.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * HoldStore Lua 스크립트 원자성 통합 테스트.
 *
 * <p><b>왜 필요한가</b>: "Redis Lua 스크립트로 원자적 좌석 선점"이 이 프로젝트의 핵심 주장이다.
 * {@link com.inyoung.ticketing.concurrency.SeatHoldConcurrencyTest}가 서비스 레벨에서
 * "100명 → 1명 성공"을 검증한다면, 이 테스트는 Redis 레이어에서 Lua 스크립트의
 * 원자성(atomicity)과 중복 방어 동작을 직접 검증한다.
 *
 * <p>검증 대상 Lua 스크립트 (CREATE_SCRIPT):
 * <pre>
 *   if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end   ← 이미 홀드된 좌석 → 즉시 거절
 *   redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])        ← 좌석 → 토큰 매핑
 *   redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[2])        ← 토큰 → 홀드 정보
 *   redis.call('ZADD', KEYS[3], ARGV[4], ARGV[3])             ← 만료 ZSET 기록
 *   return 1
 * </pre>
 *
 * <p>실제 Redis 컨테이너(Testcontainers)로 스크립트의 원자적 동작을 검증한다.</p>
 */
class HoldStoreIntegrationTest extends IntegrationTestBase {

	@Autowired private HoldStore holdStore;

	private static final Long SEAT_ID_1 = 1001L;
	private static final Long SEAT_ID_2 = 1002L;
	private static final Long CONCERT_ID = 1L;
	private static final Duration TTL = Duration.ofMinutes(10);

	@BeforeEach
	void setUp() {
		// 테스트 간 Redis 상태 격리 — 이전 홀드 정리
		holdStore.releaseHold(tokenFor("user1", SEAT_ID_1));
		holdStore.releaseHold(tokenFor("user2", SEAT_ID_1));
		holdStore.releaseHold(tokenFor("user1", SEAT_ID_2));
	}

	/**
	 * Lua 스크립트 핵심 검증: 같은 좌석에 두 번째 홀드 시도 → false.
	 *
	 * <p>분산 환경에서 "중복 좌석 선점" 사고를 막는 1차 방어선이다.
	 * Lua 스크립트가 EXISTS 체크 → SET 을 원자적으로 처리하므로
	 * 두 번째 createHold 는 사용자가 누구든 반드시 false 를 반환해야 한다.</p>
	 */
	@Test
	@DisplayName("같은 seatId 두 번째 홀드 시도 → Lua 스크립트 EXISTS 체크로 즉시 거절")
	void createHold_second_sameSeat_returnsFalse() {
		HoldInfo first = holdInfo("user1", SEAT_ID_1);
		HoldInfo second = holdInfo("user2", SEAT_ID_1); // 다른 사용자, 같은 좌석

		boolean firstResult = holdStore.createHold(first, TTL);
		boolean secondResult = holdStore.createHold(second, TTL);

		assertThat(firstResult)
			.as("첫 번째 홀드는 성공해야 한다")
			.isTrue();
		assertThat(secondResult)
			.as("같은 좌석에 두 번째 홀드는 Lua 스크립트가 즉시 거절해야 한다")
			.isFalse();
	}

	/**
	 * 좌석 단위 독립성: 다른 seatId 는 서로 영향 없이 각각 홀드 가능.
	 */
	@Test
	@DisplayName("서로 다른 seatId 는 독립적으로 홀드 가능")
	void createHold_differentSeats_bothSucceed() {
		HoldInfo hold1 = holdInfo("user1", SEAT_ID_1);
		HoldInfo hold2 = holdInfo("user2", SEAT_ID_2);

		assertThat(holdStore.createHold(hold1, TTL)).isTrue();
		assertThat(holdStore.createHold(hold2, TTL)).isTrue();
	}

	/**
	 * 홀드 해제 후 재홀드: releaseHold 후 같은 좌석을 다른 사용자가 홀드 가능해야 한다.
	 * 취소·만료 후 좌석이 다시 선택 가능한 상태로 돌아오는지 검증한다.
	 */
	@Test
	@DisplayName("홀드 해제 후 같은 좌석 재홀드 가능")
	void createHold_afterRelease_succeedsAgain() {
		HoldInfo hold1 = holdInfo("user1", SEAT_ID_1);
		holdStore.createHold(hold1, TTL);

		holdStore.releaseHold(hold1.getHoldToken());

		HoldInfo hold2 = holdInfo("user2", SEAT_ID_1);
		boolean result = holdStore.createHold(hold2, TTL);

		assertThat(result)
			.as("홀드 해제 후 같은 좌석에 새 홀드가 가능해야 한다")
			.isTrue();
	}

	/**
	 * getHold: 토큰으로 홀드 정보를 정확히 복원한다.
	 * Redis 에 저장된 JSON 페이로드가 올바르게 역직렬화되는지 확인한다.
	 */
	@Test
	@DisplayName("createHold 후 getHold → 동일한 홀드 정보 반환")
	void getHold_returnsCorrectInfo() {
		HoldInfo info = holdInfo("user1", SEAT_ID_1);
		holdStore.createHold(info, TTL);

		var retrieved = holdStore.getHold(info.getHoldToken());

		assertThat(retrieved).isPresent();
		assertThat(retrieved.get().getHoldToken()).isEqualTo(info.getHoldToken());
		assertThat(retrieved.get().getSeatId()).isEqualTo(SEAT_ID_1);
		assertThat(retrieved.get().getUserId()).isEqualTo("user1");
		assertThat(retrieved.get().getConcertId()).isEqualTo(CONCERT_ID);
	}

	/**
	 * isSeatHeldByToken: 좌석-토큰 매핑 정합성 검증.
	 * 올바른 토큰이면 true, 다른 토큰이면 false 를 반환해야 한다.
	 * 이 검증이 "내 홀드인지" 확인하는 유일한 방법이다.
	 */
	@Test
	@DisplayName("isSeatHeldByToken — 올바른 토큰: true / 다른 토큰: false")
	void isSeatHeldByToken_correctAndIncorrect() {
		HoldInfo info = holdInfo("user1", SEAT_ID_1);
		holdStore.createHold(info, TTL);

		assertThat(holdStore.isSeatHeldByToken(SEAT_ID_1, info.getHoldToken()))
			.as("올바른 토큰으로 조회 시 true")
			.isTrue();
		assertThat(holdStore.isSeatHeldByToken(SEAT_ID_1, "wrong-token"))
			.as("다른 토큰으로 조회 시 false")
			.isFalse();
	}

	/**
	 * getHold on missing token: 존재하지 않는 토큰 조회 시 empty.
	 */
	@Test
	@DisplayName("존재하지 않는 토큰 조회 → Optional.empty()")
	void getHold_missing_returnsEmpty() {
		assertThat(holdStore.getHold("nonexistent-token")).isEmpty();
	}

	// ───────────────────────────────────── 헬퍼 ─────────────────────────────────────

	private HoldInfo holdInfo(String userId, Long seatId) {
		return new HoldInfo(
			tokenFor(userId, seatId),
			CONCERT_ID,
			seatId,
			userId,
			Instant.now().plus(TTL)
		);
	}

	private String tokenFor(String userId, Long seatId) {
		return "test-hold-" + userId + "-seat-" + seatId;
	}
}
