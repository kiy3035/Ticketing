package com.inyoung.ticketing.auth.controller;

import java.security.Principal;
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

	// 서비스 주입
	public AuthApiController(UsersService usersService, ActiveUserTracker activeUserTracker) {
		this.usersService = usersService;
		this.activeUserTracker = activeUserTracker;
	}

	// 회원가입 처리
	@PostMapping("/signup")
	public ResponseEntity<Void> signup(@Valid @RequestBody SignupRequest request) {
		usersService.signup(request);
		return ResponseEntity.status(HttpStatus.CREATED).build();
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
