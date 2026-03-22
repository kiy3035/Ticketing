package com.inyoung.ticketing.auth.domain;

import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 로컬(폼) 가입과 소셜(OAuth/OIDC) 가입이 같은 테이블을 쓴다.
 * <p>
 * 소셜만 해당: {@code oauth_provider} + {@code oauth_subject} 가 있으면 IdP 계정과 매핑된 행이다.
 * 폼 가입만 한 행은 둘 다 null 일 수 있다.
 */
@Entity
@Table(
	name = "users",
	uniqueConstraints = {
		@UniqueConstraint(columnNames = { "username" }),
		@UniqueConstraint(name = "uk_users_oauth", columnNames = { "oauth_provider", "oauth_subject" })
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

	/** OAuth 가입 등 전화번호 없음 허용. SMS 알림 시 이메일로 대체 처리 */
	@Column(length = 20)
	private String phone;

	/** OAuth2 클라이언트 등록 ID (예: google). {@code oauth_subject} 와 함께 IdP 계정을 유일하게 식별한다. */
	@Column(name = "oauth_provider", length = 32)
	private String oauthProvider;

	/** IdP 가 발급한 계정 불변 ID. OpenID 에서 흔히 {@code sub} 클레임과 동일한 값을 저장한다. */
	@Column(name = "oauth_subject", length = 255)
	private String oauthSubject;

	@Column(nullable = false, length = 20)
	private String notiType = "sms";

	@Column(nullable = false, length = 20)
	private String role = "USER";  // ADMIN 또는 USER

	@Column(name = "point", nullable = false)
	private Long point = 0L;

	/** 가입 시각 (서울 시간, DB DATETIME 저장) */
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@PrePersist
	void prePersist() {
		this.createdAt = LocalDateTime.now().withNano(0);
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

	public String getOauthProvider() {
		return oauthProvider;
	}

	public void setOauthProvider(String oauthProvider) {
		this.oauthProvider = oauthProvider;
	}

	public String getOauthSubject() {
		return oauthSubject;
	}

	public void setOauthSubject(String oauthSubject) {
		this.oauthSubject = oauthSubject;
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

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}
}
