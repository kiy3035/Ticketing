package com.inyoung.ticketing.auth.dto;

import java.time.OffsetDateTime;

// 마이페이지 응답 DTO
public class MyPageResponse {
	private final String username;
		private final Long point;
	private final OffsetDateTime createdAt;

		public MyPageResponse(String username, Long point, OffsetDateTime createdAt) {
		this.username = username;
			this.point = point;
		this.createdAt = createdAt;
	}

	public String getUsername() {
		return username;
	}

		public Long getPoint() {
			return point;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}
}
