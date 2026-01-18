package com.inyoung.ticketing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

// 로그인/회원가입 및 접근 제어를 설정하는 보안 설정
@Configuration
@EnableWebSecurity
public class SecurityConfig {
	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
			// 접근 제어 규칙 정의
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/login", "/signup", "/css/**", "/js/**", "/images/**").permitAll()
				.requestMatchers("/api/auth/**").permitAll()
				.requestMatchers("/", "/app/**", "/api/**").authenticated()
				.anyRequest().authenticated()
			)
			// 커스텀 로그인 페이지 사용
			.formLogin(form -> form
				.loginPage("/login")
				.defaultSuccessUrl("/app", true)
				.permitAll()
			)
			// 로그아웃 설정
			.logout(logout -> logout
				.logoutUrl("/logout")
				.logoutSuccessUrl("/login?logout")
				.permitAll()
			)
			// API는 CSRF 제외 (간단한 프론트 호출용)
			.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
			.httpBasic(Customizer.withDefaults());

		return http.build();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		// 비밀번호 해시용 BCrypt 인코더
		return new BCryptPasswordEncoder();
	}
}
