package com.inyoung.ticketing.domain;

import java.time.Instant;
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
	name = "user_account",
	uniqueConstraints = {
		@UniqueConstraint(columnNames = { "username" })
	}
)
public class UserAccount {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 50)
	private String username;

	@Column(nullable = false, length = 120)
	private String passwordHash;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	// 생성 시각 자동 설정
	@PrePersist
	void prePersist() {
		this.createdAt = Instant.now();
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
	public String getPasswordHash() {
		return passwordHash;
	}

	// 비밀번호 해시 설정
	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
	}

	// 생성 시각
	public Instant getCreatedAt() {
		return createdAt;
	}
}
