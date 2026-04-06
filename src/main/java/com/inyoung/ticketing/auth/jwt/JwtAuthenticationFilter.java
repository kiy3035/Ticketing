package com.inyoung.ticketing.auth.jwt;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Spring Security 필터 체인 앞단에서 JWT 를 검사한다.
 * <p>
 * 요청마다 {@link SecurityContextHolder} 를 비운 뒤 {@link JwtAuthenticationService#authenticate} 를 호출한다.
 * 공개 경로({@link PublicEndpointPaths})는 필터를 건너뛰며, 그때는 인증 없이 다음 필터로 넘긴다.
 * </p>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {
	private final JwtAuthenticationService jwtAuthenticationService;

	public JwtAuthenticationFilter(JwtAuthenticationService jwtAuthenticationService) {
		this.jwtAuthenticationService = jwtAuthenticationService;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return PublicEndpointPaths.isJwtSkipped(request.getRequestURI(), request.getMethod());
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		SecurityContextHolder.clearContext();
		if (!jwtAuthenticationService.authenticate(request, response)) {
			return;
		}
		filterChain.doFilter(request, response);
	}
}
