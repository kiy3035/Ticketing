package com.inyoung.ticketing.auth.controller;

import com.inyoung.ticketing.auth.dto.AuthMeResponse;
import com.inyoung.ticketing.auth.dto.LoginRequest;
import com.inyoung.ticketing.auth.dto.MyPageResponse;
import com.inyoung.ticketing.auth.dto.SignupRequest;
import com.inyoung.ticketing.auth.dto.TokenPairResponse;
import com.inyoung.ticketing.auth.jwt.JwtAuthenticationService;
import com.inyoung.ticketing.auth.jwt.JwtTokenIssueService;
import com.inyoung.ticketing.auth.service.UsersService;
import com.inyoung.ticketing.metrics.service.ActiveUserTracker;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthApiController {
	private final UsersService usersService;
	private final ActiveUserTracker activeUserTracker;
	private final AuthenticationManager authenticationManager;
	private final JwtTokenIssueService jwtTokenIssueService;
	private final JwtAuthenticationService jwtAuthenticationService;

	public AuthApiController(
		UsersService usersService,
		ActiveUserTracker activeUserTracker,
		AuthenticationManager authenticationManager,
		JwtTokenIssueService jwtTokenIssueService,
		JwtAuthenticationService jwtAuthenticationService
	) {
		this.usersService = usersService;
		this.activeUserTracker = activeUserTracker;
		this.authenticationManager = authenticationManager;
		this.jwtTokenIssueService = jwtTokenIssueService;
		this.jwtAuthenticationService = jwtAuthenticationService;
	}

	@PostMapping("/signup")
	public ResponseEntity<Map<String, Boolean>> signup(@Valid @RequestBody SignupRequest request) {
		usersService.signup(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("success", true));
	}

	@PostMapping("/login")
	public ResponseEntity<TokenPairResponse> login(@Valid @RequestBody LoginRequest request) {
		authenticationManager.authenticate(
			new UsernamePasswordAuthenticationToken(request.username(), request.password()));
		return ResponseEntity.ok(jwtTokenIssueService.issueForUsername(request.username()));
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(
		@RequestHeader(value = "Authorization", required = false) String authorization,
		@RequestHeader(value = "X-Refresh-Token", required = false) String refreshToken,
		Authentication authentication
	) {
		if (authentication != null && authentication.isAuthenticated()
			&& !(authentication instanceof AnonymousAuthenticationToken)) {
			activeUserTracker.removeActive(authentication.getName());
		}
		jwtAuthenticationService.logout(authorization, refreshToken);
		return ResponseEntity.noContent().build();
	}

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
		return authentication.getName();
	}
}
