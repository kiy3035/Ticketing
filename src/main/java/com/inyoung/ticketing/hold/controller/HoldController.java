package com.inyoung.ticketing.hold.controller;

import java.util.List;
import com.inyoung.ticketing.hold.dto.HoldItemResponse;
import com.inyoung.ticketing.hold.dto.HoldRequest;
import com.inyoung.ticketing.hold.dto.HoldResponse;
import com.inyoung.ticketing.hold.service.HoldService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
	public HoldResponse createHold(Authentication authentication, @Valid @RequestBody HoldRequest request) {
		return holdService.createHold(request, authentication.getName());
	}

	// 내 예약 중인 홀드 목록 (결제 전 좌석 홀드)
	@GetMapping("/me")
	public List<HoldItemResponse> listMyHolds(Authentication authentication) {
		return holdService.listMyHolds(authentication.getName());
	}

	// 홀드 취소
	@DeleteMapping("/{holdToken}")
	public ResponseEntity<Void> cancelHold(Authentication authentication, @PathVariable String holdToken) {
		holdService.cancelHold(holdToken, authentication.getName());
		return ResponseEntity.noContent().build();
	}
}
