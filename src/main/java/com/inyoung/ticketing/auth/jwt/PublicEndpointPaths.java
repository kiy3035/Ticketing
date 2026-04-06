package com.inyoung.ticketing.auth.jwt;

import org.springframework.http.HttpMethod;

/**
 * {@link JwtAuthenticationFilter#shouldNotFilter} 에서 사용한다.
 * <p>
 * 여기서 true 인 요청은 JWT 검사를 하지 않으며, {@link com.inyoung.ticketing.config.SecurityConfig} 의
 * {@code permitAll} 과 논리적으로 맞춰 두었다(정적 파일, 로그인·회원가입 POST, 대기열 API, Actuator, Swagger 등).
 * </p>
 */
public final class PublicEndpointPaths {
	private PublicEndpointPaths() {
	}

	/**
	 * @param uri    요청 URI (서블릿에서의 경로)
	 * @param method HTTP 메서드
	 * @return true 이면 JWT 필터 스킵
	 */
	public static boolean isJwtSkipped(String uri, String method) {
		if (uri.startsWith("/actuator")) {
			return true;
		}
		if (uri.startsWith("/css/") || uri.startsWith("/js/") || uri.startsWith("/images/")) {
			return true;
		}
		if (uri.equals("/") || uri.equals("/favicon.ico")) {
			return true;
		}
		if (uri.endsWith(".html")) {
			return true;
		}
		if (uri.startsWith("/api/queue/")) {
			return true;
		}
		if (uri.startsWith("/api-docs") || uri.startsWith("/swagger-ui") || uri.equals("/swagger-ui.html")
			|| uri.startsWith("/v3/api-docs")) {
			return true;
		}
		if (HttpMethod.POST.matches(method)
			&& ("/api/auth/login".equals(uri) || "/api/auth/signup".equals(uri))) {
			return true;
		}
		return false;
	}
}
