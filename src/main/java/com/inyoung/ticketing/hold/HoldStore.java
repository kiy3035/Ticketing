package com.inyoung.ticketing.hold;

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
		return result != null && result == 1L;
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
