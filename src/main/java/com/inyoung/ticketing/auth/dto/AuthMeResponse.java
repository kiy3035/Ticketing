package com.inyoung.ticketing.auth.dto;

/**
 * 로그인 사용자 정보 응답 DTO
 * 
 * 사용자 이름과 역할(ADMIN/USER)을 포함하여 클라이언트에 반환합니다.
 * 클라이언트에서 role에 따라 리다이렉팅 여부를 결정합니다.
 */
public class AuthMeResponse {
	private String username;
	private String role;

	public AuthMeResponse(String username, String role) {
		this.username = username;
		this.role = role;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getRole() {
		return role;
	}

	public void setRole(String role) {
		this.role = role;
	}
}
