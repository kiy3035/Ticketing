package com.inyoung.ticketing.auth.jwt;

import com.inyoung.ticketing.auth.dto.TokenPairResponse;
import com.inyoung.ticketing.auth.service.UsersService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 성공 직후 Access·Refresh 한 쌍을 만들고, Refresh jti 를 DB 에 기록한다.
 * <p>
 * {@link com.inyoung.ticketing.auth.controller.AuthApiController} 의 {@code POST /api/auth/login} 에서만 사용한다.
 * 트랜잭션으로 묶어 토큰 발급과 DB insert 가 함께 커밋되도록 한다.
 * </p>
 */
@Service
public class JwtTokenIssueService {
	private final JwtTokenService jwtTokenService;
	private final RefreshTokenPersistenceService refreshTokenPersistenceService;
	private final UsersService usersService;

	public JwtTokenIssueService(
		JwtTokenService jwtTokenService,
		RefreshTokenPersistenceService refreshTokenPersistenceService,
		UsersService usersService
	) {
		this.jwtTokenService = jwtTokenService;
		this.refreshTokenPersistenceService = refreshTokenPersistenceService;
		this.usersService = usersService;
	}

	/**
	 * 인증된 사용자명으로 역할을 조회한 뒤 Access·Refresh 를 발급하고 Refresh 메타를 저장한다.
	 *
	 * @return 클라이언트에 내려갈 토큰 쌍(응답은 전역 {@link com.inyoung.ticketing.common.api.ApiResponseAdvice} 에 의해 래핑될 수 있음)
	 */
	@Transactional
	public TokenPairResponse issueForUsername(String username) {
		String role = usersService.loadUserRole(username);
		String refreshJti = jwtTokenService.newJti();
		String access = jwtTokenService.createAccessToken(username, role);
		String refresh = jwtTokenService.createRefreshToken(username, refreshJti);
		refreshTokenPersistenceService.saveNew(refreshJti, username, jwtTokenService.newRefreshExpiryDateTime());
		return new TokenPairResponse(access, refresh, "Bearer");
	}
}
