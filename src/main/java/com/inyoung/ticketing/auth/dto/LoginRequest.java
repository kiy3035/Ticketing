package com.inyoung.ticketing.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/auth/login} 요청 본문. 폼 로그인 대신 JSON 으로 받는다.
 */
public record LoginRequest(
	@NotBlank String username,
	@NotBlank String password
) {
}
