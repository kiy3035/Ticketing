package com.inyoung.ticketing.auth.domain;

import com.inyoung.ticketing.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 로컬 회원가입 사용자 계정.
 */
@Entity
@Table(
	name = "users",
	uniqueConstraints = @UniqueConstraint(columnNames = { "username" })
)
public class Users extends BaseEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 50)
	private String username;

	@Column(name = "pw", nullable = false, length = 120)
	private String pw;

	@Column(nullable = false, length = 100)
	private String email;

	/** 비어 있을 수 있음. SMS 알림 시 번호 없으면 이메일 등으로 분기 */
	@Column(length = 20)
	private String phone;

	@Column(nullable = false, length = 20)
	private String notiType = "sms";

	@Column(nullable = false, length = 20)
	private String role = "USER";  // ADMIN 또는 USER

	@Column(name = "point", nullable = false)
	private Long point = 0L;

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
}
