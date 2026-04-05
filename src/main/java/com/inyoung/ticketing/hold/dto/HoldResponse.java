package com.inyoung.ticketing.hold.dto;

import java.time.Instant;

/**
 * 홀드 생성 성공 시 클라이언트에 돌려주는 값.
 * record 이므로 API 응답 JSON은 {@code holdToken}, {@code expiresAt} 키로 직렬화된다(일반 클래스의 getter 명명 규칙과 동일한 관례).
 */
public record HoldResponse(String holdToken, Instant expiresAt) {
}
