package com.inyoung.ticketing.controller;

import java.security.Principal;
import com.inyoung.ticketing.dto.SignupRequest;
import com.inyoung.ticketing.service.ActiveUserTracker;
import com.inyoung.ticketing.service.UsersService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

	// 로그인 사용자 아이디 조회
	@GetMapping("/me")
	public ResponseEntity<String> me(Principal principal) {
		activeUserTracker.recordActive(principal.getName());
		return ResponseEntity.ok(principal.getName());
	}
}
