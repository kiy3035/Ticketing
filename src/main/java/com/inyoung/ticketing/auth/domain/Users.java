package com.inyoung.ticketing.auth.domain;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

// 사용자 계정 엔티티
@Entity
@Table(
	name = "users",
	uniqueConstraints = {
		@UniqueConstraint(columnNames = { "username" })
	}
)
public class Users {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 50)
	private String username;

	@Column(name = "pw", nullable = false, length = 120)
	private String pw;

	@Column(name = "point", nullable = false)
	private Long point = 0L;

	@Column(nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	// 생성 시각 자동 설정
	@PrePersist
	void prePersist() {
		this.createdAt = OffsetDateTime.now(ZoneId.of("Asia/Seoul"));
	}

	// 식별자
	public Long getId() {
		return id;
	}

	// 사용자 아이디
	public String getUsername() {
		return username;
	}

	// 사용자 아이디 설정
	public void setUsername(String username) {
		this.username = username;
	}

	// 비밀번호 해시
	public String getPw() {
		return pw;
	}

	// 비밀번호 해시 설정
	public void setPw(String pw) {
		this.pw = pw;
	}

	// 포인트
	public Long getPoint() {
		return point;
	}

	// 포인트 설정
	public void setPoint(Long point) {
		this.point = point;
	}

	// 생성 시각
	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}
}
