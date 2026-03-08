package com.inyoung.ticketing.concert.controller;

import java.util.List;
import com.inyoung.ticketing.concert.dto.ConcertResponse;
import com.inyoung.ticketing.concert.service.ConcertService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 콘서트 조회 API 컨트롤러
@RestController
@RequestMapping("/api/concerts")
public class ConcertController {
	private final ConcertService concertService;

	public ConcertController(ConcertService concertService) {
		this.concertService = concertService;
	}

	// 콘서트 목록 조회. past=false(기본): 예매 가능 공연, past=true: 지난 공연(오늘 날짜·현재 시간 기준)
	@GetMapping
	public List<ConcertResponse> listConcerts(
		@RequestParam(required = false) String query,
		@RequestParam(required = false) String category,
		@RequestParam(required = false, defaultValue = "false") boolean past
	) {
		return concertService.listConcerts(query, category, past);
	}
}
