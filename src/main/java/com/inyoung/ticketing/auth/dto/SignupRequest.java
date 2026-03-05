package com.inyoung.ticketing.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 회원가입 요청 DTO
public class SignupRequest {
	@NotBlank
	@Size(min = 4, max = 20)
	private String username;

	@NotBlank
	@Size(min = 6, max = 50)
	private String password;

	@NotBlank
	@Email
	private String email;

	@NotBlank
	@Pattern(regexp = "^\\d{3}-\\d{4}-\\d{4}$")
	private String phone;

	@NotBlank
	private String notificationMethod = "sms";

	/** 가입 유형: USER(일반 고객), SELLER(판매자). 미입력 시 USER */
	@Pattern(regexp = "^(USER|SELLER)?$", message = "role must be USER or SELLER")
	private String role = "USER";

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

	// 이메일
	public String getEmail() {
		return email;
	}

	// 이메일 설정
	public void setEmail(String email) {
		this.email = email;
	}

	// 휴대폰번호
	public String getPhone() {
		return phone;
	}

	// 휴대폰번호 설정
	public void setPhone(String phone) {
		this.phone = phone;
	}

	// 알림 방식
	public String getNotificationMethod() {
		return notificationMethod;
	}

	// 알림 방식 설정
	public void setNotificationMethod(String notificationMethod) {
		this.notificationMethod = notificationMethod;
	}

	public String getRole() {
		return role;
	}

	public void setRole(String role) {
		this.role = role;
	}
}
