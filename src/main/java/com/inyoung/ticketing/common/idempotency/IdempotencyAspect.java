package com.inyoung.ticketing.common.idempotency;

import java.time.Duration;
import com.inyoung.ticketing.common.exception.BusinessException;
import com.inyoung.ticketing.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@link Idempotent} 어노테이션이 붙은 메서드에 대해 멱등성을 보장하는 AOP Aspect.
 *
 * <p>동작 흐름:
 * <ol>
 *   <li>요청 헤더에서 {@code Idempotency-Key}를 추출</li>
 *   <li>키가 없으면 그냥 통과 (멱등성 선택 사항)</li>
 *   <li>이미 결과가 저장되어 있으면 캐시된 결과 반환</li>
 *   <li>PROCESSING 상태면 409 충돌</li>
 *   <li>키 선점 후 실제 로직 실행 → 결과 저장</li>
 *   <li>실패 시 키 해제 (재시도 허용)</li>
 * </ol>
 */
@Aspect
@Component
public class IdempotencyAspect {
	private static final Logger log = LoggerFactory.getLogger(IdempotencyAspect.class);
	private static final String HEADER_NAME = "Idempotency-Key";

	private final IdempotencyService idempotencyService;

	public IdempotencyAspect(IdempotencyService idempotencyService) {
		this.idempotencyService = idempotencyService;
	}

	@Around("@annotation(idempotent)")
	public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
		String idempotencyKey = extractIdempotencyKey();
		if (idempotencyKey == null) {
			return joinPoint.proceed();
		}

		Duration ttl = Duration.ofSeconds(idempotent.ttlSeconds());

		// 이전 결과가 있으면 바로 반환
		var cached = idempotencyService.getResult(idempotencyKey, Object.class);
		if (cached.isPresent()) {
			log.debug("멱등성 캐시 히트: key={}", idempotencyKey);
			return cached.get();
		}

		// 다른 요청이 처리 중이면 충돌
		if (idempotencyService.isProcessing(idempotencyKey)) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}

		// 키 선점 시도
		if (!idempotencyService.acquireKey(idempotencyKey, ttl)) {
			throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
		}

		try {
			Object result = joinPoint.proceed();
			idempotencyService.saveResult(idempotencyKey, result, ttl);
			return result;
		} catch (Exception e) {
			idempotencyService.releaseKey(idempotencyKey);
			throw e;
		}
	}

	private String extractIdempotencyKey() {
		ServletRequestAttributes attrs =
			(ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
		if (attrs == null) {
			return null;
		}
		HttpServletRequest request = attrs.getRequest();
		String key = request.getHeader(HEADER_NAME);
		return (key != null && !key.isBlank()) ? key.trim() : null;
	}
}
