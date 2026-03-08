package com.inyoung.ticketing.seat.controller;

import java.util.List;
import com.inyoung.ticketing.seat.dto.SeatResponse;
import com.inyoung.ticketing.seat.service.SeatService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 좌석 조회 API 컨트롤러
@RestController
@RequestMapping("/api/concerts/{concertId}/seats")
public class SeatController {
	private final SeatService seatService;

	public SeatController(SeatService seatService) {
		this.seatService = seatService;
	}

	// 콘서트별 좌석 목록 조회
	@GetMapping
	public List<SeatResponse> listSeats(@PathVariable Long concertId) {
		return seatService.listSeats(concertId);
	}
}
