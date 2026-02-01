package com.inyoung.ticketing.queue.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.inyoung.ticketing.config.TicketingProperties;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

// Redis 기반 콘서트별 대기열 서비스
@Service
public class QueueService {
	private static final String QUEUE_CONCERT_KEY_PREFIX = "queue:concert:";
	private static final String QUEUE_TOKEN_KEY_PREFIX = "queue:token:";
	private static final String QUEUE_ALLOWED_KEY_PREFIX = "queue:allowed:";

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;
	private final TicketingProperties properties;

	// Redis 템플릿 및 설정 주입
	public QueueService(StringRedisTemplate redisTemplate, TicketingProperties properties) {
		this.redisTemplate = redisTemplate;
		this.properties = properties;
		this.objectMapper = new ObjectMapper()
			.registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
	}

	// 콘서트 대기열 진입 (토큰 발급)
	public QueueTokenInfo enterQueue(Long concertId, String userId) {
		String queueKey = queueKey(concertId);
		// 공정성을 위해 기존 토큰은 제거하고 새로 발급한다.
		removeExistingTokens(concertId, userId);

		// 새 토큰 발급
		String token = UUID.randomUUID().toString();
		long now = System.currentTimeMillis();
		
		QueueTokenData tokenData = new QueueTokenData(userId, concertId, Instant.ofEpochMilli(now));
		String tokenDataJson = toJson(tokenData);
		
		String tokenKey = tokenKey(token);
		long tokenTtlSeconds = properties.getQueue().getTokenTtlSeconds();
		redisTemplate.opsForValue().set(tokenKey, tokenDataJson, Duration.ofSeconds(tokenTtlSeconds));
		redisTemplate.opsForZSet().add(queueKey, token, now);
		
		Long rank = getRank(concertId, token);
		Long totalWaiting = countWaiting(concertId);
		
		return new QueueTokenInfo(token, rank, totalWaiting);
	}

	// 대기 순번 조회
	public Long getRank(Long concertId, String token) {
		String queueKey = queueKey(concertId);
		Long rank = redisTemplate.opsForZSet().rank(queueKey, token);
		return rank != null ? rank + 1 : null;
	}

	// 콘서트별 대기인원 수 조회
	public Long countWaiting(Long concertId) {
		String queueKey = queueKey(concertId);
		Long size = redisTemplate.opsForZSet().size(queueKey);
		return size == null ? 0L : size;
	}

	// 입장 허용 여부 확인
	public Optional<Long> isAllowed(String token) {
		String allowedKey = allowedKey(token);
		String allowedDataJson = redisTemplate.opsForValue().get(allowedKey);
		if (allowedDataJson == null || allowedDataJson.isBlank()) {
			return Optional.empty();
		}
		QueueAllowedData allowedData = fromJson(allowedDataJson, QueueAllowedData.class);
		return Optional.of(allowedData.getConcertId());
	}

	// 입장 허용 상태 설정
	public void allowEntry(String token, Long concertId) {
		String allowedKey = allowedKey(token);
		QueueAllowedData allowedData = new QueueAllowedData(concertId, Instant.now());
		String allowedDataJson = toJson(allowedData);
		long tokenTtlSeconds = properties.getQueue().getTokenTtlSeconds();
		redisTemplate.opsForValue().set(allowedKey, allowedDataJson, Duration.ofSeconds(tokenTtlSeconds));
	}

	// 대기열에서 상위 N명 조회 (스케줄러용)
	public List<String> getTopTokens(Long concertId, int limit) {
		String queueKey = queueKey(concertId);
		Set<String> tokens = redisTemplate.opsForZSet().range(queueKey, 0, limit - 1);
		if (tokens == null) {
			return List.of();
		}
		return tokens.stream().toList();
	}

	// 만료된 토큰 정리 (토큰 키가 없는 항목 제거)
	public int pruneExpiredTokens(Long concertId, int maxScan) {
		String queueKey = queueKey(concertId);
		ScanOptions options = ScanOptions.scanOptions().count(maxScan).build();
		List<String> expiredTokens = new ArrayList<>();
		int scanned = 0;

		try (Cursor<ZSetOperations.TypedTuple<String>> cursor =
			redisTemplate.opsForZSet().scan(queueKey, options)) {
			while (cursor.hasNext() && scanned < maxScan) {
				ZSetOperations.TypedTuple<String> tuple = cursor.next();
				scanned++;
				if (tuple == null || tuple.getValue() == null) {
					continue;
				}
				String token = tuple.getValue();
				Boolean exists = redisTemplate.hasKey(tokenKey(token));
				if (Boolean.FALSE.equals(exists)) {
					expiredTokens.add(token);
				}
			}
		}

		if (!expiredTokens.isEmpty()) {
			redisTemplate.opsForZSet().remove(queueKey, expiredTokens.toArray());
		}
		return expiredTokens.size();
	}

	// 토큰 정보 조회
	public Optional<QueueTokenData> getTokenData(String token) {
		String tokenKey = tokenKey(token);
		String tokenDataJson = redisTemplate.opsForValue().get(tokenKey);
		if (tokenDataJson == null || tokenDataJson.isBlank()) {
			return Optional.empty();
		}
		return Optional.of(fromJson(tokenDataJson, QueueTokenData.class));
	}

	// 대기열에서 나가기
	public void exitQueue(Long concertId, String token) {
		String queueKey = queueKey(concertId);
		redisTemplate.opsForZSet().remove(queueKey, token);
		String tokenKey = tokenKey(token);
		redisTemplate.delete(tokenKey);
		String allowedKey = allowedKey(token);
		redisTemplate.delete(allowedKey);
	}

	// 기존 토큰 제거 (동일 사용자, 동일 콘서트)
	private void removeExistingTokens(Long concertId, String userId) {
		String queueKey = queueKey(concertId);
		Set<String> tokens = redisTemplate.opsForZSet().range(queueKey, 0, -1);
		if (tokens == null) {
			return;
		}
		for (String token : tokens) {
			Optional<QueueTokenData> tokenData = getTokenData(token);
			if (tokenData.isPresent() && tokenData.get().getUserId().equals(userId)) {
				redisTemplate.opsForZSet().remove(queueKey, token);
				redisTemplate.delete(tokenKey(token));
				redisTemplate.delete(allowedKey(token));
			}
		}
	}

	private String queueKey(Long concertId) {
		return QUEUE_CONCERT_KEY_PREFIX + concertId;
	}

	private String tokenKey(String token) {
		return QUEUE_TOKEN_KEY_PREFIX + token;
	}

	private String allowedKey(String token) {
		return QUEUE_ALLOWED_KEY_PREFIX + token;
	}

	private String toJson(Object obj) {
		try {
			return objectMapper.writeValueAsString(obj);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Queue data serialize failed", e);
		}
	}

	private <T> T fromJson(String json, Class<T> clazz) {
		try {
			return objectMapper.readValue(json, clazz);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Queue data parse failed", e);
		}
	}

	// 대기열 토큰 정보 (내부 클래스)
	public static class QueueTokenInfo {
		private final String token;
		private final Long rank;
		private final Long totalWaiting;

		public QueueTokenInfo(String token, Long rank, Long totalWaiting) {
			this.token = token;
			this.rank = rank;
			this.totalWaiting = totalWaiting;
		}

		public String getToken() {
			return token;
		}

		public Long getRank() {
			return rank;
		}

		public Long getTotalWaiting() {
			return totalWaiting;
		}
	}

	// 대기열 토큰 데이터 (Redis 저장용)
	public static class QueueTokenData {
		private String userId;
		private Long concertId;
		private Instant enteredAt;

		public QueueTokenData() {
		}

		public QueueTokenData(String userId, Long concertId, Instant enteredAt) {
			this.userId = userId;
			this.concertId = concertId;
			this.enteredAt = enteredAt;
		}

		public String getUserId() {
			return userId;
		}

		public void setUserId(String userId) {
			this.userId = userId;
		}

		public Long getConcertId() {
			return concertId;
		}

		public void setConcertId(Long concertId) {
			this.concertId = concertId;
		}

		public Instant getEnteredAt() {
			return enteredAt;
		}

		public void setEnteredAt(Instant enteredAt) {
			this.enteredAt = enteredAt;
		}
	}

	// 입장 허용 데이터 (Redis 저장용)
	public static class QueueAllowedData {
		private Long concertId;
		private Instant allowedAt;

		public QueueAllowedData() {
		}

		public QueueAllowedData(Long concertId, Instant allowedAt) {
			this.concertId = concertId;
			this.allowedAt = allowedAt;
		}

		public Long getConcertId() {
			return concertId;
		}

		public void setConcertId(Long concertId) {
			this.concertId = concertId;
		}

		public Instant getAllowedAt() {
			return allowedAt;
		}

		public void setAllowedAt(Instant allowedAt) {
			this.allowedAt = allowedAt;
		}
	}
}
