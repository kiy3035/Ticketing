package com.inyoung.ticketing.auth.jwt;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import com.inyoung.ticketing.auth.service.UsersService;
import com.inyoung.ticketing.config.TicketingProperties;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * 보호된 요청마다 Access·Refresh 를 검사하고, 네 가지 만료 조합에 따라 재발급 또는 401 을 결정한다.
 * <p>
 * <b>전제</b>: 인증이 필요한 API에는 {@code Authorization: Bearer} 와 {@code X-Refresh-Token} 이 함께 온다(단, SSE 는 쿼리 파라미터 예외).
 * 이미 폐기된 Refresh jti 재사용 시(가족 탈취 탐지) 해당 family의 모든 Refresh를 무효화하고 401 을 반환한다.
 * <b>Case 1</b> 둘 다 만료 → 401<br>
 * <b>Case 2</b> Access 만 만료 → Refresh DB 검증 후 새 Access·Refresh(회전), {@code X-New-Access-Token}·{@code X-New-Refresh-Token}<br>
 * <b>Case 3</b> Refresh 만 만료 → Access 블랙리스트 확인 후 새 Refresh 발급·DB 갱신(동일 family), {@code X-New-Refresh-Token}<br>
 * <b>Case 4</b> 둘 다 유효 → Access 블랙리스트·Refresh DB 검증 후 {@link SecurityContextHolder} 설정<br>
 * </p>
 */
@Service
public class JwtAuthenticationService {
	private final JwtTokenService jwtTokenService;
	private final TokenBlacklistService tokenBlacklistService;
	private final RefreshTokenPersistenceService refreshTokenPersistenceService;
	private final UsersService usersService;
	private final TicketingProperties ticketingProperties;

	public JwtAuthenticationService(
		JwtTokenService jwtTokenService,
		TokenBlacklistService tokenBlacklistService,
		RefreshTokenPersistenceService refreshTokenPersistenceService,
		UsersService usersService,
		TicketingProperties ticketingProperties
	) {
		this.jwtTokenService = jwtTokenService;
		this.tokenBlacklistService = tokenBlacklistService;
		this.refreshTokenPersistenceService = refreshTokenPersistenceService;
		this.usersService = usersService;
		this.ticketingProperties = ticketingProperties;
	}

	/**
	 * 토큰 검사 후 성공 시 true, 401 을 이미 쓴 경우 false.
	 */
	public boolean authenticate(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String access = extractAccessToken(request);
		String refresh = extractRefreshToken(request);
		if (access == null || access.isBlank() || refresh == null || refresh.isBlank()) {
			writeUnauthorized(response);
			return false;
		}

		var accessOpt = jwtTokenService.parseSignedClaimsLenient(access);
		var refreshOpt = jwtTokenService.parseSignedClaimsLenient(refresh);
		if (accessOpt.isEmpty() || refreshOpt.isEmpty()) {
			writeUnauthorized(response);
			return false;
		}

		Claims ac = accessOpt.get();
		Claims rc = refreshOpt.get();

		if (!JwtTokenService.TYP_ACCESS.equals(ac.get(JwtTokenService.CLAIM_TYP))
			|| !JwtTokenService.TYP_REFRESH.equals(rc.get(JwtTokenService.CLAIM_TYP))) {
			writeUnauthorized(response);
			return false;
		}

		String subA = ac.getSubject();
		String subR = rc.getSubject();
		if (subA == null || subR == null || !subA.equals(subR)) {
			writeUnauthorized(response);
			return false;
		}

		boolean accessExpired = jwtTokenService.isExpired(ac);
		boolean refreshExpired = jwtTokenService.isExpired(rc);
		String accessJti = ac.getId();
		String refreshJti = rc.getId();

		if (refreshTokenPersistenceService.detectReuseOfRevokedRefreshAndInvalidateFamily(refreshJti)) {
			writeUnauthorized(response);
			return false;
		}

		// Case 1: 둘 다 만료 → 재로그인
		if (accessExpired && refreshExpired) {
			writeUnauthorized(response);
			return false;
		}

		// Case 2: Access 만 만료 — Refresh JWT·DB 가 유효하면 Access 재발급 + Refresh 회전(동일 family)
		if (accessExpired) {
			if (!refreshTokenPersistenceService.isValidStoredRefresh(refreshJti, subR)) {
				writeUnauthorized(response);
				return false;
			}
			String newRefreshJti = jwtTokenService.newJti();
			String newRefresh = jwtTokenService.createRefreshToken(subR, newRefreshJti);
			LocalDateTime refreshExp = LocalDateTime.now().plusDays(ticketingProperties.getJwt().getRefreshTtlDays());
			refreshTokenPersistenceService.rotateRefreshAfterAccessRenewal(refreshJti, subR, newRefreshJti, refreshExp);
			String role = usersService.loadUserRole(subR);
			String newAccess = jwtTokenService.createAccessToken(subR, role);
			setSecurityContext(subR, role);
			response.setHeader("X-New-Access-Token", newAccess);
			response.setHeader("X-New-Refresh-Token", newRefresh);
			return true;
		}

		// Case 3: Refresh 만 만료 — Access 가 살아 있고 블랙리스트가 아니면 새 Refresh 발급·DB 교체
		if (refreshExpired) {
			if (tokenBlacklistService.isAccessBlacklisted(accessJti)) {
				writeUnauthorized(response);
				return false;
			}
			String role = (String) ac.get(JwtTokenService.CLAIM_ROLE);
			if (role == null || role.isBlank()) {
				role = usersService.loadUserRole(subR);
			}
			String newJti = jwtTokenService.newJti();
			String newRefresh = jwtTokenService.createRefreshToken(subR, newJti);
			String familyId = refreshTokenPersistenceService.findFamilyIdByJti(refreshJti)
				.orElseGet(() -> jwtTokenService.newJti());
			refreshTokenPersistenceService.revokeByJti(refreshJti);
			LocalDateTime refreshExp = LocalDateTime.now().plusDays(ticketingProperties.getJwt().getRefreshTtlDays());
			refreshTokenPersistenceService.saveNew(newJti, subR, refreshExp, familyId);
			setSecurityContext(subR, role);
			response.setHeader("X-New-Refresh-Token", newRefresh);
			return true;
		}

		// Case 4: 둘 다 유효 — 로그아웃 블랙리스트·DB Refresh 행까지 확인
		if (tokenBlacklistService.isAccessBlacklisted(accessJti)) {
			writeUnauthorized(response);
			return false;
		}
		if (!refreshTokenPersistenceService.isValidStoredRefresh(refreshJti, subR)) {
			writeUnauthorized(response);
			return false;
		}
		String role = (String) ac.get(JwtTokenService.CLAIM_ROLE);
		if (role == null || role.isBlank()) {
			role = usersService.loadUserRole(subR);
		}
		setSecurityContext(subR, role);
		return true;
	}

	/**
	 * 로그아웃: Refresh jti 는 항상 revoke 시도, Access 는 아직 만료 전이면 jti 를 Redis 블랙리스트에 넣는다.
	 */
	public void logout(String authorizationHeader, String refreshHeader) {
		String access = bearerValue(authorizationHeader);
		String refresh = refreshHeader != null ? refreshHeader.trim() : null;
		if (refresh != null && !refresh.isBlank()) {
			jwtTokenService.parseSignedClaimsLenient(refresh).ifPresent(rc -> {
				if (JwtTokenService.TYP_REFRESH.equals(rc.get(JwtTokenService.CLAIM_TYP)) && rc.getId() != null) {
					refreshTokenPersistenceService.revokeEntireFamilyByRefreshJti(rc.getId());
				}
			});
		}
		if (access != null && !access.isBlank()) {
			jwtTokenService.parseSignedClaimsLenient(access).ifPresent(ac -> {
				if (JwtTokenService.TYP_ACCESS.equals(ac.get(JwtTokenService.CLAIM_TYP))
					&& ac.getId() != null
					&& ac.getExpiration() != null
					&& !jwtTokenService.isExpired(ac)) {
					tokenBlacklistService.blacklistAccessJti(ac.getId(), ac.getExpiration().toInstant());
				}
			});
		}
	}

	private static String bearerValue(String authorizationHeader) {
		if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
			return null;
		}
		return authorizationHeader.substring(7).trim();
	}

	/**
	 * Access: 표준 {@code Authorization} 헤더. SSE 는 {@code EventSource} 가 헤더를 못 붙이므로 쿼리 {@code accessToken} 허용.
	 */
	private static String extractAccessToken(HttpServletRequest request) {
		String auth = request.getHeader("Authorization");
		if (auth != null && auth.startsWith("Bearer ")) {
			return auth.substring(7).trim();
		}
		if ("/api/notifications/stream".equals(request.getRequestURI())) {
			String q = request.getParameter("accessToken");
			return q != null ? q.trim() : null;
		}
		return null;
	}

	/**
	 * Refresh: {@code X-Refresh-Token} 헤더. SSE 시 {@code refreshToken} 쿼리.
	 */
	private static String extractRefreshToken(HttpServletRequest request) {
		String r = request.getHeader("X-Refresh-Token");
		if (r != null && !r.isBlank()) {
			return r.trim();
		}
		if ("/api/notifications/stream".equals(request.getRequestURI())) {
			String q = request.getParameter("refreshToken");
			return q != null ? q.trim() : null;
		}
		return null;
	}

	private static void setSecurityContext(String username, String role) {
		String r = role != null && !role.startsWith("ROLE_") ? "ROLE_" + role : role;
		var auth = new UsernamePasswordAuthenticationToken(
			username,
			null,
			Collections.singletonList(new SimpleGrantedAuthority(r != null ? r : "ROLE_USER"))
		);
		SecurityContextHolder.getContext().setAuthentication(auth);
	}

	private static void writeUnauthorized(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType("application/json;charset=UTF-8");
		response.getWriter().write("{\"error\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다.\"}");
	}
}
