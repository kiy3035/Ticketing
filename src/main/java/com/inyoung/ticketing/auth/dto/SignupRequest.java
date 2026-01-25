package com.inyoung.ticketing.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 회원가입 요청 DTO
public class SignupRequest {
	@NotBlank
	@Size(min = 4, max = 20)
	private String username;

	@NotBlank
	@Size(min = 6, max = 50)
	private String password;

	// 사용자 아이디
	public String getUsername() {
		return username;
	}

	// 사용자 아이디 설정
	public void setUsername(String username) {
		this.username = username;
	}

	// 비밀번호
	public String getPassword() {
		return password;
	}

	// 비밀번호 설정
	public void setPassword(String password) {
		this.password = password;
	}
}
