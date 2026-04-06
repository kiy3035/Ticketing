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
 * Refresh JWT 의 jti 를 DB 에 보관하는 엔티티.
 * <p>
 * {@link #familyId}: 동일 로그인 세션에서 발급·회전된 Refresh 들이 공유한다. 재사용(탈취) 탐지 시 이 ID 기준으로 전부 폐기한다.
 * </p>
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

	/**
	 * 로그인 시 한 번 발급되며, Refresh 회전 시에도 같은 값을 유지해 한 세션의 토큰 끼리 묶는다.
	 */
	@Column(name = "family_id", nullable = false, length = 36)
	private String familyId;

	/** JWT {@code jti} 와 동일하게 저장해 검증 시 조회한다. */
	@Column(nullable = false, length = 36, unique = true)
	private String jti;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

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

	public String getFamilyId() {
		return familyId;
	}

	public void setFamilyId(String familyId) {
		this.familyId = familyId;
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
