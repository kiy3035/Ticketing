package com.inyoung.ticketing.auth.jwt;

import java.time.LocalDateTime;
import java.util.Optional;
import com.inyoung.ticketing.auth.domain.Users;
import com.inyoung.ticketing.auth.jwt.domain.RefreshToken;
import com.inyoung.ticketing.auth.jwt.repository.RefreshTokenRepository;
import com.inyoung.ticketing.auth.repository.UsersRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh JWT 의 jti 를 DB 에 저장·검증·폐기한다.
 * 로그아웃 또는 만료된 Refresh 재발급 시 해당 jti 행을 {@code revoked = true} 로 표시한다.
 */
@Service
public class RefreshTokenPersistenceService {
	private final RefreshTokenRepository refreshTokenRepository;
	private final UsersRepository usersRepository;

	public RefreshTokenPersistenceService(
		RefreshTokenRepository refreshTokenRepository,
		UsersRepository usersRepository
	) {
		this.refreshTokenRepository = refreshTokenRepository;
		this.usersRepository = usersRepository;
	}

	/** 새 Refresh 발급 시 행 삽입 (로그인 또는 만료 후 재발급). */
	@Transactional
	public void saveNew(String jti, String username, LocalDateTime expiresAt) {
		Users user = usersRepository.findByUsername(username)
			.orElseThrow(() -> new IllegalStateException("User not found: " + username));
		RefreshToken entity = new RefreshToken();
		entity.setUser(user);
		entity.setJti(jti);
		entity.setExpiresAt(expiresAt);
		entity.setRevoked(false);
		refreshTokenRepository.save(entity);
	}

	/**
	 * JWT 서명·subject 와 함께, DB 에서 해당 jti 가 유효(미폐기·만료 전)·사용자 일치 여부를 본다.
	 */
	@Transactional(readOnly = true)
	public boolean isValidStoredRefresh(String jti, String username) {
		Optional<RefreshToken> opt = refreshTokenRepository.findByJti(jti);
		if (opt.isEmpty()) {
			return false;
		}
		RefreshToken rt = opt.get();
		if (rt.isRevoked()) {
			return false;
		}
		if (rt.getExpiresAt().isBefore(LocalDateTime.now())) {
			return false;
		}
		return rt.getUser().getUsername().equals(username);
	}

	/** 단일 jti 폐기 (로그아웃 시 사용) */
	@Transactional
	public void revokeByJti(String jti) {
		refreshTokenRepository.findByJti(jti).ifPresent(rt -> rt.setRevoked(true));
	}
}
