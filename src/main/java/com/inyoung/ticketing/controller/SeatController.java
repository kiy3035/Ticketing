package com.inyoung.ticketing.controller;

import java.util.List;
import com.inyoung.ticketing.dto.SeatResponse;
import com.inyoung.ticketing.service.SeatService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/concerts/{concertId}/seats")
public class SeatController {
	private final SeatService seatService;

	public SeatController(SeatService seatService) {
		this.seatService = seatService;
	}

	@GetMapping
	public List<SeatResponse> listSeats(@PathVariable Long concertId) {
		return seatService.listSeats(concertId);
	}
}
