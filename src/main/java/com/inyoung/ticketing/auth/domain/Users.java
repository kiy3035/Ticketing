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

	@Column(nullable = false, length = 100)
	private String email;

	@Column(nullable = false, length = 20)
	private String phone;

	@Column(nullable = false, length = 20)
	private String notiType = "sms";

	@Column(nullable = false, length = 20)
	private String role = "USER";  // ADMIN 또는 USER

	@Column(name = "point", nullable = false)
	private Long point = 0L;

	@Column(nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	// 생성 시각 자동 설정 (한국시간, 초단위까지만 저장)
	@PrePersist
	void prePersist() {
		this.createdAt = OffsetDateTime.now(ZoneId.of("Asia/Seoul")).withNano(0);
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
	public String getNotiType() {
		return notiType;
	}

	// 알림 방식 설정
	public void setNotiType(String notiType) {
		this.notiType = notiType;
	}

	// 포인트
	public Long getPoint() {
		return point;
	}

	// 포인트 설정
	public void setPoint(Long point) {
		this.point = point;
	}

	// 역할 (ADMIN 또는 USER)
	public String getRole() {
		return role;
	}

	// 역할 설정
	public void setRole(String role) {
		this.role = role;
	}

	// 생성 시각
	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}
}
