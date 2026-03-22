package com.inyoung.ticketing.auth.controller;

import java.security.Principal;
import java.util.Map;
import com.inyoung.ticketing.auth.dto.AuthMeResponse;
import com.inyoung.ticketing.auth.dto.MyPageResponse;
import com.inyoung.ticketing.auth.dto.SignupRequest;
import com.inyoung.ticketing.auth.service.UsersService;
import com.inyoung.ticketing.metrics.service.ActiveUserTracker;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 회원가입 및 사용자 정보 API
@RestController
@RequestMapping("/api/auth")
public class AuthApiController {
	private final UsersService usersService;
	private final ActiveUserTracker activeUserTracker;

	public AuthApiController(UsersService usersService, ActiveUserTracker activeUserTracker) {
		this.usersService = usersService;
		this.activeUserTracker = activeUserTracker;
	}

	// 회원가입 처리 (빈 본문 201은 브라우저 fetch + JSON 파싱에서 깨질 수 있어 명시적 JSON 반환)
	@PostMapping("/signup")
	public ResponseEntity<Map<String, Boolean>> signup(@Valid @RequestBody SignupRequest request) {
		usersService.signup(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("success", true));
	}

	// 로그인 사용자 정보 조회 (이름 및 역할)
	@GetMapping("/me")
	public ResponseEntity<AuthMeResponse> me(Authentication authentication) {
		if (authentication == null
			|| !authentication.isAuthenticated()
			|| authentication instanceof AnonymousAuthenticationToken) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		String name = extractName(authentication);
		if (name == null || name.isBlank()) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		activeUserTracker.recordActive(name);
		String role = usersService.loadUserRole(name);
		return ResponseEntity.ok(new AuthMeResponse(name, role));
	}

	// 마이페이지 정보 조회
	@GetMapping("/me/profile")
	public ResponseEntity<MyPageResponse> myPage(Authentication authentication) {
		if (authentication == null
			|| !authentication.isAuthenticated()
			|| authentication instanceof AnonymousAuthenticationToken) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		String name = extractName(authentication);
		if (name == null || name.isBlank()) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		activeUserTracker.recordActive(name);
		return ResponseEntity.ok(usersService.loadMyPage(name));
	}

	/**
	 * 세션에 올라온 인증 정보에서 "우리 서비스 로그인 ID" 문자열을 꺼낸다.
	 * 폼 로그인은 {@link org.springframework.security.core.userdetails.UserDetails#getUsername()},
	 * OAuth/OIDC 는 {@link org.springframework.security.oauth2.core.user.OAuth2User} 등이 {@link java.security.Principal} 로
	 * 올 때 {@link Principal#getName()} 이 {@link com.inyoung.ticketing.auth.oauth.OAuth2UserAttributeNames#INTERNAL_USERNAME} 으로
	 * 맞춰져 있어 DB 의 {@code username} 과 같다.
	 */
	private String extractName(Authentication authentication) {
		Object principal = authentication.getPrincipal();
		if (principal instanceof UserDetails userDetails) {
			return userDetails.getUsername();
		}
		if (principal instanceof Principal named) {
			return named.getName();
		}
		return authentication.getName();
	}
}
