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
 * <p>
 * <b>familyId</b>: 로그인 한 번당 하나의 UUID 로 묶고, 회전(rotation) 시 같은 family 로 새 jti 행을 쌓는다.
 * <b>탈취 탐지</b>: 이미 {@code revoked} 인 jti 로 요청이 오면(회전 후 구 Refresh 재사용) 같은 family 의 모든 행을 무효화한다.
 * </p>
 */
@Service
public class RefreshTokenPersistenceService {
	private final RefreshTokenRepository refreshTokenRepository;
	private final UsersRepository usersRepository;
	private final JwtTokenService jwtTokenService;

	public RefreshTokenPersistenceService(
		RefreshTokenRepository refreshTokenRepository,
		UsersRepository usersRepository,
		JwtTokenService jwtTokenService
	) {
		this.refreshTokenRepository = refreshTokenRepository;
		this.usersRepository = usersRepository;
		this.jwtTokenService = jwtTokenService;
	}

	/** 최초 로그인 또는 가족이 정해진 뒤 새 Refresh 행 삽입 */
	@Transactional
	public void saveNew(String jti, String username, LocalDateTime expiresAt, String familyId) {
		Users user = usersRepository.findByUsername(username)
			.orElseThrow(() -> new IllegalStateException("User not found: " + username));
		RefreshToken entity = new RefreshToken();
		entity.setUser(user);
		entity.setFamilyId(familyId);
		entity.setJti(jti);
		entity.setExpiresAt(expiresAt);
		entity.setRevoked(false);
		refreshTokenRepository.save(entity);
	}

	/**
	 * DB 에 해당 jti 가 있고 이미 폐기된 경우 → 회전된 구 토큰 재사용으로 간주해 가족 전체를 무효화한다.
	 *
	 * @return true 이면 탈취 의심 처리까지 끝났으므로 요청은 401 로 끝낸다.
	 */
	@Transactional
	public boolean detectReuseOfRevokedRefreshAndInvalidateFamily(String refreshJti) {
		Optional<RefreshToken> opt = refreshTokenRepository.findByJti(refreshJti);
		if (opt.isEmpty()) {
			return false;
		}
		RefreshToken rt = opt.get();
		if (!rt.isRevoked()) {
			return false;
		}
		refreshTokenRepository.revokeAllByFamilyId(rt.getFamilyId());
		return true;
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

	@Transactional(readOnly = true)
	public Optional<String> findFamilyIdByJti(String jti) {
		return refreshTokenRepository.findByJti(jti).map(RefreshToken::getFamilyId);
	}

	/** 단일 jti 만 폐기(회전 직전 구 토큰 등) */
	@Transactional
	public void revokeByJti(String jti) {
		refreshTokenRepository.findByJti(jti).ifPresent(rt -> rt.setRevoked(true));
	}

	/**
	 * Case 3 (Access 유효·Refresh 만료) 전용: 구 jti 폐기 + 새 jti 저장을 한 트랜잭션으로 처리한다.
	 * 두 작업이 분리되면 서버 장애 시 구 토큰만 revoked 되고 새 토큰이 없는 상태가 생긴다.
	 * 스케일아웃(서버 2대) 환경에서는 두 트랜잭션 사이에 다른 서버가 같은 jti를 처리해
	 * 탈취로 오인·family 전체 무효화가 발생할 수 있어 원자성이 필수다.
	 *
	 * @param oldJti     폐기할 구 Refresh jti
	 * @param newJti     새로 발급할 jti
	 * @param username   토큰 소유자
	 * @param newExpiresAt 새 토큰 만료 시각
	 */
	@Transactional
	public void replaceRefresh(String oldJti, String newJti, String username, LocalDateTime newExpiresAt) {
		String familyId = refreshTokenRepository.findByJti(oldJti)
			.map(rt -> {
				rt.setRevoked(true);
				return rt.getFamilyId();
			})
			.orElseGet(() -> jwtTokenService.newJti());
		saveNew(newJti, username, newExpiresAt, familyId);
	}

	/**
	 * Access 재발급(Case 2) 시 Refresh 도 함께 회전: 구 jti 는 폐기하고 동일 family 로 새 행을 넣는다.
	 */
	@Transactional
	public void rotateRefreshAfterAccessRenewal(String oldRefreshJti, String username, String newRefreshJti, LocalDateTime newExpiresAt) {
		RefreshToken old = refreshTokenRepository.findByJti(oldRefreshJti)
			.orElseThrow(() -> new IllegalStateException("Refresh jti not found: " + oldRefreshJti));
		String familyId = old.getFamilyId();
		old.setRevoked(true);
		saveNew(newRefreshJti, username, newExpiresAt, familyId);
	}

	/** 로그아웃: 해당 Refresh 가 속한 family 의 모든 Refresh 를 무효화한다. */
	@Transactional
	public void revokeEntireFamilyByRefreshJti(String refreshJti) {
		refreshTokenRepository.findByJti(refreshJti).ifPresent(rt ->
			refreshTokenRepository.revokeAllByFamilyId(rt.getFamilyId()));
	}
}
