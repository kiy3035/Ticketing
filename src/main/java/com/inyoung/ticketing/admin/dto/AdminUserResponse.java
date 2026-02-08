package com.inyoung.ticketing.admin.dto;

/**
 * 관리 인터페이스용 사용자 응답 DTO
 */
public class AdminUserResponse {
	private Long id;
	private String username;
	private String email;
	private String phone;
	private Long point;
	private String role;
	private String createdAt;

	public AdminUserResponse(Long id, String username, String email, String phone, Long point, String role, String createdAt) {
		this.id = id;
		this.username = username;
		this.email = email;
		this.phone = phone;
		this.point = point;
		this.role = role;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public String getUsername() {
		return username;
	}

	public String getEmail() {
		return email;
	}

	public String getPhone() {
		return phone;
	}

	public Long getPoint() {
		return point;
	}

	public String getRole() {
		return role;
	}

	public String getCreatedAt() {
		return createdAt;
	}
}
