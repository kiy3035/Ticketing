package com.inyoung.ticketing.common.idempotency;

import java.time.Duration;
import java.util.Optional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis 기반 멱등성 키 저장소.
 * 키 형식: {@code idempotency:{key}} → 처리 결과 JSON을 저장하고 TTL을 적용한다.
 * 처리 중에는 {@code "PROCESSING"} 마커를 넣어 동시 중복 요청을 차단한다.
 */
@Service
public class IdempotencyService {
	private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
	private static final String KEY_PREFIX = "idempotency:";
	private static final String PROCESSING_MARKER = "__PROCESSING__";

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	public IdempotencyService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
	}

	/**
	 * 키를 선점한다. 이미 선점된 키면 false, 새로 선점하면 true.
	 * 선점 시 PROCESSING 마커를 넣어 다른 요청이 중복 실행되지 못하게 한다.
	 */
	public boolean acquireKey(String idempotencyKey, Duration ttl) {
		String redisKey = KEY_PREFIX + idempotencyKey;
		Boolean set = redisTemplate.opsForValue().setIfAbsent(redisKey, PROCESSING_MARKER, ttl);
		return Boolean.TRUE.equals(set);
	}

	/** 처리 완료 후 결과를 저장한다. */
	public void saveResult(String idempotencyKey, Object result, Duration ttl) {
		String redisKey = KEY_PREFIX + idempotencyKey;
		try {
			String json = objectMapper.writeValueAsString(result);
			redisTemplate.opsForValue().set(redisKey, json, ttl);
		} catch (JsonProcessingException e) {
			log.warn("멱등성 결과 직렬화 실패: key={}", idempotencyKey, e);
		}
	}

	/** 이전에 저장된 결과를 조회한다. 없거나 PROCESSING 중이면 empty. */
	public <T> Optional<T> getResult(String idempotencyKey, Class<T> resultType) {
		String redisKey = KEY_PREFIX + idempotencyKey;
		String json = redisTemplate.opsForValue().get(redisKey);
		if (json == null || PROCESSING_MARKER.equals(json)) {
			return Optional.empty();
		}
		try {
			return Optional.of(objectMapper.readValue(json, resultType));
		} catch (JsonProcessingException e) {
			log.warn("멱등성 결과 역직렬화 실패: key={}", idempotencyKey, e);
			return Optional.empty();
		}
	}

	/** 키가 존재하는지 (PROCESSING 중 포함) 확인한다. */
	public boolean isProcessing(String idempotencyKey) {
		String redisKey = KEY_PREFIX + idempotencyKey;
		String val = redisTemplate.opsForValue().get(redisKey);
		return PROCESSING_MARKER.equals(val);
	}

	/** 처리 실패 시 키를 제거해 재시도를 허용한다. */
	public void releaseKey(String idempotencyKey) {
		redisTemplate.delete(KEY_PREFIX + idempotencyKey);
	}
}
