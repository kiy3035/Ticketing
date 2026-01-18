package com.inyoung.ticketing.controller;

import com.inyoung.ticketing.dto.ReservationRequest;
import com.inyoung.ticketing.dto.ReservationResponse;
import com.inyoung.ticketing.service.ReservationService;
import jakarta.validation.Valid;
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
	public ReservationResponse confirm(@Valid @RequestBody ReservationRequest request) {
		return reservationService.confirm(request);
	}
}
