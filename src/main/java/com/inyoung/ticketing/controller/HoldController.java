package com.inyoung.ticketing.controller;

import com.inyoung.ticketing.dto.HoldCreateRequest;
import com.inyoung.ticketing.dto.HoldResponse;
import com.inyoung.ticketing.service.HoldService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 홀드 생성/취소 API 컨트롤러
@RestController
@RequestMapping("/api/holds")
public class HoldController {
	private final HoldService holdService;

	// 서비스 주입
	public HoldController(HoldService holdService) {
		this.holdService = holdService;
	}

	// 좌석 홀드 생성
	@PostMapping
	public HoldResponse createHold(Authentication authentication, @Valid @RequestBody HoldCreateRequest request) {
		return holdService.createHold(request, authentication.getName());
	}

	// 홀드 취소
	@DeleteMapping("/{holdToken}")
	public ResponseEntity<Void> cancelHold(Authentication authentication, @PathVariable String holdToken) {
		holdService.cancelHold(holdToken, authentication.getName());
		return ResponseEntity.noContent().build();
	}
}
