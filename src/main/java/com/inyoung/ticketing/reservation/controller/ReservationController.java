package com.inyoung.ticketing.reservation.controller;

import java.util.List;
import com.inyoung.ticketing.reservation.dto.ReservationItemResponse;
import com.inyoung.ticketing.reservation.dto.ReservationRequest;
import com.inyoung.ticketing.reservation.dto.ReservationResponse;
import com.inyoung.ticketing.reservation.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 예약 확정 API 컨트롤러
@RestController
@RequestMapping("/api/reservations")
public class ReservationController {
	private final ReservationService reservationService;

	// 서비스 주입
	public ReservationController(ReservationService reservationService) {
		this.reservationService = reservationService;
	}

	// 홀드 토큰으로 예약 확정
	@PostMapping
	public ReservationResponse confirm(Authentication authentication, @Valid @RequestBody ReservationRequest request) {
		return reservationService.confirm(request, authentication.getName());
	}

	// 로그인 사용자 예매내역 조회
	@GetMapping("/me")
	public List<ReservationItemResponse> list(Authentication authentication) {
		return reservationService.listByUser(authentication.getName());
	}
}
