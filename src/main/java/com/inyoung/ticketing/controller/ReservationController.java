package com.inyoung.ticketing.controller;

import com.inyoung.ticketing.dto.ReservationRequest;
import com.inyoung.ticketing.dto.ReservationResponse;
import com.inyoung.ticketing.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {
	private final ReservationService reservationService;

	public ReservationController(ReservationService reservationService) {
		this.reservationService = reservationService;
	}

	@PostMapping
	public ReservationResponse confirm(@Valid @RequestBody ReservationRequest request) {
		return reservationService.confirm(request);
	}
}
