package com.inyoung.ticketing.common.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 멱등성 보장이 필요한 API 메서드에 부착한다.
 * 클라이언트가 {@code Idempotency-Key} 헤더를 보내면,
 * 동일 키로 중복 요청이 들어올 경우 이전 응답을 그대로 반환하거나 409를 반환한다.
 *
 * <p>결제 요청처럼 네트워크 재시도로 인한 중복 처리를 방지할 때 사용한다.</p>
 *
 * @see IdempotencyAspect
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
	/** 멱등성 키 결과의 TTL(초). 기본 24시간. */
	long ttlSeconds() default 86400;
}
