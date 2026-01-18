package com.inyoung.ticketing.controller;

import java.util.List;
import com.inyoung.ticketing.dto.ConcertResponse;
import com.inyoung.ticketing.service.ConcertService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 콘서트 조회 API 컨트롤러
@RestController
@RequestMapping("/api/concerts")
public class ConcertController {
	private final ConcertService concertService;

	// 서비스 주입
	public ConcertController(ConcertService concertService) {
		this.concertService = concertService;
	}

	// 콘서트 목록 조회
	@GetMapping
	public List<ConcertResponse> listConcerts() {
		return concertService.listConcerts();
	}
}
