package com.inyoung.ticketing.config;

import java.io.IOException;
import com.inyoung.ticketing.auth.oauth.TicketingOAuth2UserService;
import com.inyoung.ticketing.auth.oauth.TicketingOidcUserService;
import com.inyoung.ticketing.metrics.service.ActiveUserTracker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * 로그인·로그아웃·OAuth2·URL 접근 제어를 한 곳에서 설정한다.
 * <p>
 * <b>소셜 로그인(OAuth2 Client) 설정 포인트</b>
 * <ul>
 *   <li>{@code /oauth2/authorization/{registrationId}} : 사용자가 "Google로 로그인" 링크를 누르면 여기로 들어가
 *       브라우저가 Google 로그인 페이지로 리다이렉트된다. (Spring Security 가 경로 제공)</li>
 *   <li>{@code /login/oauth2/code/{registrationId}} : Google 이 동의 후 돌려보내는 <b>콜백 URL</b>(Authorization Code 가 쿼리로 옴).
 *       로그인하지 않은 사용자도 이 단계를 거쳐야 하므로 {@code permitAll} 이다.</li>
 *   <li>{@code oauth2Login()} : 위 리다이렉트·코드 교환·UserInfo 호출·세션에 인증 저장까지 처리.
 *       {@code userService} / {@code oidcUserService} 에 우리가 만든 빈을 넣어 "로그인 성공 후 우리 DB 와 어떻게 연결할지"를 정한다.</li>
 * </ul>
 * Google 은 {@code openid} 스코프 때문에 <b>OIDC</b> 경로를 타므로 {@link com.inyoung.ticketing.auth.oauth.TicketingOidcUserService} 가 필수이고,
 * {@link com.inyoung.ticketing.auth.oauth.TicketingOAuth2UserService} 는 순수 OAuth2 UserInfo 만 쓰는 IdP 용이다.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
	private final ActiveUserTracker activeUserTracker;
	private final TicketingOAuth2UserService ticketingOAuth2UserService;
	private final TicketingOidcUserService ticketingOidcUserService;

	public SecurityConfig(
		ActiveUserTracker activeUserTracker,
		@Lazy TicketingOAuth2UserService ticketingOAuth2UserService,
		@Lazy TicketingOidcUserService ticketingOidcUserService
	) {
		this.activeUserTracker = activeUserTracker;
		this.ticketingOAuth2UserService = ticketingOAuth2UserService;
		this.ticketingOidcUserService = ticketingOidcUserService;
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
			// 접근 제어 규칙 정의
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/").permitAll() // 루트 경로는 RootController에서 리다이렉트 처리
				.requestMatchers("/login.html", "/signup.html", "/css/**", "/js/**", "/images/**").permitAll()
				.requestMatchers("/favicon.ico").permitAll() // 브라우저 자동 요청 허용
				.requestMatchers("/login", "/logout").permitAll()
				// OAuth2: 로그인 시작(/oauth2/authorization/...) 및 콜백(/login/oauth2/code/...)은 인증 전 단계이므로 누구나 접근
				.requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
				.requestMatchers("/api/auth/signup").permitAll()
				.requestMatchers("/api/queue/**").permitAll() // 부하 테스트용 허용
				.requestMatchers("/actuator/**").permitAll() // Prometheus 스크래핑 및 헬스체크
				.requestMatchers("/admin.html", "/app.html", "/seller.html", "/concert.html", "/queue.html", "/reservation.html", "/payment.html", "/api/**").authenticated()
				.anyRequest().authenticated()
			)
			// 커스텀 로그인 페이지 사용
			.formLogin(form -> form
				.loginPage("/login.html")
				.loginProcessingUrl("/login")
				.successHandler(loginSuccessHandler())
				.permitAll()
			)
			// 소셜 로그인: 성공 시 폼 로그인과 동일하게 loginSuccessHandler(역할별 리다이렉트)
			.oauth2Login(oauth2 -> oauth2
				.loginPage("/login.html")
				.userInfoEndpoint(userInfo -> userInfo
					// OAuth2 전용 UserInfo (openid 없이 userinfo 만 쓰는 등록용)
					.userService(ticketingOAuth2UserService)
					// Google + openid 스코프 → 반드시 OIDC 쪽 핸들러 사용
					.oidcUserService(ticketingOidcUserService))
				.successHandler(loginSuccessHandler())
			)
			// 로그아웃 설정
			.logout(logout -> logout
				.logoutUrl("/logout")
			.invalidateHttpSession(true)
			.deleteCookies("JSESSIONID")
				.logoutSuccessHandler((request, response, authentication) -> {
					if (authentication != null) {
						activeUserTracker.removeActive(authentication.getName());
					}
					sendRedirect(response, "/login.html?logout");
				})
				.permitAll()
			)
			// 단순 데모용으로 CSRF 비활성화
			.csrf(csrf -> csrf.disable());

		return http.build();
	}

	/**
	 * 폼 로그인·OAuth 로그인 공통: 인증 성공 직후 {@code authentication.getName()} 은
	 * 폼에서는 DB username, OAuth 에서는 {@link com.inyoung.ticketing.auth.oauth.OAuth2UserAttributeNames#INTERNAL_USERNAME} 으로 맞춘 username 이다.
	 */
	private AuthenticationSuccessHandler loginSuccessHandler() {
		return (HttpServletRequest request, HttpServletResponse response, Authentication authentication) -> {
			activeUserTracker.recordActive(authentication.getName());
			String redirect = "/app.html";
			boolean isAdmin = authentication.getAuthorities().stream()
				.anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
			boolean isSeller = authentication.getAuthorities().stream()
				.anyMatch(a -> "ROLE_SELLER".equals(a.getAuthority()));
			if (isAdmin) {
				redirect = "/admin.html";
			} else if (isSeller) {
				redirect = "/seller.html";
			}
			sendRedirect(response, redirect);
		};
	}

	private void sendRedirect(jakarta.servlet.http.HttpServletResponse response, String location)
		throws IOException {
		response.sendRedirect(location);
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		// 비밀번호 해시용 BCrypt 인코더
		return new BCryptPasswordEncoder();
	}
}
