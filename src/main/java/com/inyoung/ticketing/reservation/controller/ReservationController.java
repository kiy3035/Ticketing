package com.inyoung.ticketing.reservation.controller;

import java.util.List;
import com.inyoung.ticketing.reservation.dto.ReservationItemResponse;
import com.inyoung.ticketing.reservation.service.ReservationService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예약 API 컨트롤러.
 * 예약 확정은 결제 완료(POST /api/payments/{paymentKey}/complete) 시에만 이루어지며,
 * 별도 예약 확정 엔드포인트는 제공하지 않는다.
 */
@RestController
@RequestMapping("/api/reservations")
public class ReservationController {
	private final ReservationService reservationService;

	public ReservationController(ReservationService reservationService) {
		this.reservationService = reservationService;
	}

	// 로그인 사용자 예매내역 조회
	@GetMapping("/me")
	public List<ReservationItemResponse> list(Authentication authentication) {
		return reservationService.listByUser(authentication.getName());
	}
}
