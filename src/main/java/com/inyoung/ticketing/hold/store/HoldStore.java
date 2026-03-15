package com.inyoung.ticketing.hold.store;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

// Redis 기반 홀드 저장소
@Component
public class HoldStore {
	private static final String SEAT_KEY_PREFIX = "hold:seat:";
	private static final String TOKEN_KEY_PREFIX = "hold:token:";
	private static final String EXPIRY_ZSET_KEY = "hold:expires";
	private static final String USER_HOLDS_PREFIX = "hold:user:";

	private static final DefaultRedisScript<Long> CREATE_SCRIPT = new DefaultRedisScript<>(
		"""
		if redis.call('EXISTS', KEYS[1]) == 1 then
			return 0
		end
		redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
		redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[2])
		redis.call('ZADD', KEYS[3], ARGV[4], ARGV[3])
		return 1
		""",
		Long.class
	);

	private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
		"""
		if redis.call('GET', KEYS[1]) == ARGV[1] then
			redis.call('DEL', KEYS[1])
		end
		redis.call('DEL', KEYS[2])
		redis.call('ZREM', KEYS[3], ARGV[2])
		return 1
		""",
		Long.class
	);

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	public HoldStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
	}

	public boolean createHold(HoldInfo info, Duration ttl) {
		// 좌석 홀드 생성은 원자적으로 처리한다.
		// 1) 좌석 키가 없을 때만 생성 (중복 홀드 방지)
		// 2) 좌석 -> 토큰, 토큰 -> 홀드 정보를 TTL과 함께 저장
		// 3) 만료 시각을 ZSET에 기록해 스케줄러가 스캔할 수 있게 한다.
		String seatKey = seatKey(info.getSeatId());
		String tokenKey = tokenKey(info.getHoldToken());
		String payload = toPayload(info);
		List<String> keys = List.of(seatKey, tokenKey, EXPIRY_ZSET_KEY);
		Long result = redisTemplate.execute(
			CREATE_SCRIPT,
			keys,
			info.getHoldToken(),
			String.valueOf(ttl.toSeconds()),
			payload,
			String.valueOf(info.getExpiresAt().toEpochMilli())
		);
		if (result != null && result == 1L) {
			redisTemplate.opsForSet().add(userHoldsKey(info.getUserId()), info.getHoldToken());
			return true;
		}
		return false;
	}

	public Optional<HoldInfo> getHold(String holdToken) {
		String payload = redisTemplate.opsForValue().get(tokenKey(holdToken));
		if (payload == null || payload.isBlank()) {
			return Optional.empty();
		}
		return Optional.of(fromPayload(payload));
	}

	public boolean isSeatHeldByToken(Long seatId, String holdToken) {
		String token = redisTemplate.opsForValue().get(seatKey(seatId));
		return holdToken.equals(token);
	}

	/**
	 * 결제 진행 중 홀드 TTL 연장 (20분 제한 적용).
	 * 결제 요청 시 호출하여 해당 홀드의 만료 시각을 연장한다.
	 * Redis의 seat/token 키 TTL과 만료 ZSET 스코어를 함께 갱신한다.
	 *
	 * @param holdToken 홀드 토큰
	 * @param newTtl    연장할 TTL (예: 20분)
	 * @return 연장 성공 시 true, 홀드가 없으면 false
	 */
	public boolean extendHoldTtl(String holdToken, Duration newTtl) {
		String payload = redisTemplate.opsForValue().get(tokenKey(holdToken));
		if (payload == null || payload.isBlank()) {
			return false;
		}
		HoldInfo info = fromPayload(payload);
		Instant newExpiresAt = Instant.now().plus(newTtl);
		HoldInfo newInfo = new HoldInfo(
			info.getHoldToken(),
			info.getConcertId(),
			info.getSeatId(),
			info.getUserId(),
			newExpiresAt
		);
		String newPayload = toPayload(newInfo);
		long ttlSeconds = newTtl.toSeconds();

		String seatKey = seatKey(info.getSeatId());
		String tokenKey = tokenKey(holdToken);
		redisTemplate.opsForValue().set(seatKey, holdToken, Duration.ofSeconds(ttlSeconds));
		redisTemplate.opsForValue().set(tokenKey, newPayload, Duration.ofSeconds(ttlSeconds));
		// ZSET: 기존 payload 제거 후 새 만료 시각으로 추가
		redisTemplate.opsForZSet().remove(EXPIRY_ZSET_KEY, payload);
		redisTemplate.opsForZSet().add(EXPIRY_ZSET_KEY, newPayload, newExpiresAt.toEpochMilli());
		return true;
	}

	public Optional<HoldInfo> releaseHold(String holdToken) {
		String payload = redisTemplate.opsForValue().get(tokenKey(holdToken));
		if (payload == null || payload.isBlank()) {
			return Optional.empty();
		}
		HoldInfo info = fromPayload(payload);
		releaseByPayload(info, payload);
		return Optional.of(info);
	}

	public void releaseByPayload(HoldInfo info, String payload) {
		List<String> keys = List.of(seatKey(info.getSeatId()), tokenKey(info.getHoldToken()), EXPIRY_ZSET_KEY);
		redisTemplate.execute(RELEASE_SCRIPT, keys, info.getHoldToken(), payload);
		redisTemplate.opsForSet().remove(userHoldsKey(info.getUserId()), info.getHoldToken());
	}

	public Set<Long> findHeldSeatIds(List<Long> seatIds) {
		if (seatIds.isEmpty()) {
			return Set.of();
		}
		List<String> keys = seatIds.stream().map(this::seatKey).toList();
		List<String> values = redisTemplate.opsForValue().multiGet(keys);
		if (values == null) {
			return Set.of();
		}
		Set<Long> held = new HashSet<>();
		for (int i = 0; i < values.size(); i++) {
			if (values.get(i) != null) {
				held.add(seatIds.get(i));
			}
		}
		return held;
	}

	/**
	 * 사용자별 유효한 홀드 목록 (만료 전인 것만). 토큰이 이미 만료되어 없으면 Set에서 제거(정리).
	 */
	public List<HoldInfo> getHoldsByUser(String userId) {
		Set<String> tokens = redisTemplate.opsForSet().members(userHoldsKey(userId));
		if (tokens == null || tokens.isEmpty()) {
			return List.of();
		}
		Instant now = Instant.now();
		List<HoldInfo> result = new ArrayList<>();
		for (String token : tokens) {
			String payload = redisTemplate.opsForValue().get(tokenKey(token));
			if (payload == null || payload.isBlank()) {
				redisTemplate.opsForSet().remove(userHoldsKey(userId), token);
				continue;
			}
			HoldInfo info = fromPayload(payload);
			if (info.getExpiresAt().isBefore(now)) {
				continue;
			}
			result.add(info);
		}
		return result;
	}

	/**
	 * 만료되지 않은 현재 활성 홀드 수 (ZSET에서 score > now 인 항목 수).
	 * 메트릭 Gauge용.
	 */
	public long countActiveHolds() {
		long now = Instant.now().toEpochMilli();
		Long count = redisTemplate.opsForZSet().count(EXPIRY_ZSET_KEY, (double) now, Double.POSITIVE_INFINITY);
		return count != null ? count : 0L;
	}

	public List<HoldPayload> findExpiredHolds(Instant now, int limit) {
		// hold:expires ZSET에서 만료 시각이 지난 항목을 조회한다.
		// 조회 결과는 스케줄러가 Redis에서 제거하고 Kafka 이벤트 발행에 사용한다.
		ZSetOperations<String, String> zset = redisTemplate.opsForZSet();
		Set<String> payloads = zset.rangeByScore(EXPIRY_ZSET_KEY, 0, now.toEpochMilli(), 0, limit);
		if (payloads == null || payloads.isEmpty()) {
			return List.of();
		}
		List<HoldPayload> result = new ArrayList<>();
		for (String payload : payloads) {
			if (payload == null || payload.isBlank()) {
				continue;
			}
			result.add(new HoldPayload(fromPayload(payload), payload));
		}
		return result;
	}

	private String seatKey(Long seatId) {
		return SEAT_KEY_PREFIX + seatId;
	}

	private String tokenKey(String holdToken) {
		return TOKEN_KEY_PREFIX + holdToken;
	}

	private String userHoldsKey(String userId) {
		return USER_HOLDS_PREFIX + userId;
	}

	private String toPayload(HoldInfo info) {
		try {
			return objectMapper.writeValueAsString(info);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Hold payload serialize failed", e);
		}
	}

	private HoldInfo fromPayload(String payload) {
		try {
			return objectMapper.readValue(payload, HoldInfo.class);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Hold payload parse failed", e);
		}
	}
}
