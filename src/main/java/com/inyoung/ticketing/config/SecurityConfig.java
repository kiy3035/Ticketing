package com.inyoung.ticketing.config;

import java.io.IOException;
import com.inyoung.ticketing.metrics.service.ActiveUserTracker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

// 로그인/회원가입 및 접근 제어를 설정하는 보안 설정
@Configuration
@EnableWebSecurity
public class SecurityConfig {
	private final ActiveUserTracker activeUserTracker;

	public SecurityConfig(ActiveUserTracker activeUserTracker) {
		this.activeUserTracker = activeUserTracker;
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
				.requestMatchers("/api/auth/signup").permitAll()
				.requestMatchers("/api/queue/**").permitAll() // 부하 테스트용 허용
				.requestMatchers("/actuator/**").permitAll() // Prometheus 스크래핑 및 헬스체크
				.requestMatchers("/admin.html", "/app.html", "/concert.html", "/queue.html", "/reservation.html", "/payment.html", "/api/**").authenticated()
				.anyRequest().authenticated()
			)
			// 커스텀 로그인 페이지 사용
			.formLogin(form -> form
				.loginPage("/login.html")
				.loginProcessingUrl("/login")
				.successHandler((request, response, authentication) -> {
					activeUserTracker.recordActive(authentication.getName());
					sendRedirect(response, "/app.html");
				})
				.permitAll()
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
