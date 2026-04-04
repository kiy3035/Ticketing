package com.inyoung.ticketing.common.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * API Rate Limiting 어노테이션.
 * Redis Sliding Window 알고리즘 기반으로 사용자별 요청 수를 제한한다.
 *
 * <p>기본값은 초당 10회이며, 어노테이션 파라미터로 API별 커스텀 가능하다.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
	/** 윈도우 내 최대 요청 수. 기본 10. */
	int maxRequests() default 10;
	/** 윈도우 크기(초). 기본 1초. */
	int windowSeconds() default 1;
}
