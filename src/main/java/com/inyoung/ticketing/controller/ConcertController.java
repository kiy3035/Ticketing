package com.inyoung.ticketing.controller;

import java.util.List;
import com.inyoung.ticketing.dto.ConcertResponse;
import com.inyoung.ticketing.service.ConcertService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/concerts")
public class ConcertController {
	private final ConcertService concertService;

	public ConcertController(ConcertService concertService) {
		this.concertService = concertService;
	}

	@GetMapping
	public List<ConcertResponse> listConcerts() {
		return concertService.listConcerts();
	}
}
