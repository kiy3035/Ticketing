package com.inyoung.ticketing.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 로그인 API 가 반환하는 액세스·리프레시 토큰 쌍.
 * <p>
 * JSON 필드명은 프론트·k6 와 맞추기 위해 camelCase 로 직렬화한다.
 * </p>
 */
public record TokenPairResponse(
	@JsonProperty("accessToken") String accessToken,
	@JsonProperty("refreshToken") String refreshToken,
	@JsonProperty("tokenType") String tokenType
) {
}
