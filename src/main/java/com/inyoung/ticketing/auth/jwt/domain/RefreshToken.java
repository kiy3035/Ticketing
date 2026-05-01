package com.inyoung.ticketing.auth.jwt.domain;

import java.time.LocalDateTime;
import com.inyoung.ticketing.auth.domain.Users;
import com.inyoung.ticketing.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * ════════════════════════════════════════════════════════════════
 * [RefreshToken 엔티티 — JWT Refresh 토큰 관리]
 *
 * ■ 왜 Refresh 토큰을 DB에 저장하나?
 *   JWT는 기본적으로 "stateless" — 서버가 토큰을 저장하지 않아도 서명 검증만으로 인증 가능.
 *   그런데 Refresh 토큰은 아래 이유로 DB에 저장한다:
 *
 *   1. 폐기(revoke) 기능이 필요하다.
 *      로그아웃 시 해당 Refresh 토큰을 revoked = true로 표시해 재사용을 막는다.
 *      Refresh 토큰을 DB에 저장하지 않으면 만료 전에 폐기할 방법이 없다.
 *
 *   2. 토큰 재발급 시 검증이 필요하다.
 *      사용자가 Access 토큰 만료 후 Refresh 토큰으로 재발급 요청 시,
 *      jti(JWT ID)로 DB를 조회해 이미 폐기된 토큰으로 재발급하는 것을 막는다.
 *
 * ■ jti (JWT ID)
 *   - JWT 스펙(RFC 7519)에 정의된 고유 식별자 클레임.
 *   - UUID 등으로 생성하며, 이 값을 DB의 jti 컬럼과 맞춰 저장/조회한다.
 *   - unique = true: 같은 jti가 두 번 발급될 수 없도록 DB 유니크 보장.
 *
 * ■ @ManyToOne(fetch = FetchType.LAZY) to Users
 *   - 한 사용자는 여러 Refresh 토큰을 가질 수 있다 (여러 기기 로그인).
 *   - 토큰 검증 시 Users 객체 자체가 필요한 경우는 드물다.
 *     LAZY로 설정해 Users를 기본적으로 로딩하지 않음.
 *   - 단점: 토큰 검증 후 사용자 정보가 필요하면 별도 쿼리가 실행됨.
 *
 * ■ revoked 필드
 *   - true: 폐기된 토큰 (로그아웃, 비밀번호 변경 등)
 *   - false: 유효한 토큰
 *   - 토큰 재발급 API에서 revoked = true인 토큰 요청은 인증 거부.
 * ════════════════════════════════════════════════════════════════
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private Users user;

	/** JWT jti 클레임 값과 동일하게 저장. 검증 시 이 값으로 조회한다. */
	@Column(nullable = false, length = 36, unique = true)
	private String jti;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	/**
	 * 폐기 여부.
	 * false: 유효, true: 폐기(로그아웃·재발급 시 이전 토큰 무효화).
	 * DB 레벨의 boolean은 MySQL에서 TINYINT(1)로 저장됨.
	 */
	@Column(nullable = false)
	private boolean revoked;

	public Long getId() {
		return id;
	}

	public Users getUser() {
		return user;
	}

	public void setUser(Users user) {
		this.user = user;
	}

	public String getJti() {
		return jti;
	}

	public void setJti(String jti) {
		this.jti = jti;
	}

	public LocalDateTime getExpiresAt() {
		return expiresAt;
	}

	public void setExpiresAt(LocalDateTime expiresAt) {
		this.expiresAt = expiresAt;
	}

	public boolean isRevoked() {
		return revoked;
	}

	public void setRevoked(boolean revoked) {
		this.revoked = revoked;
	}
}
