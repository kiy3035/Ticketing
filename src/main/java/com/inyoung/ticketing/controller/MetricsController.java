package com.inyoung.ticketing.controller;

import com.inyoung.ticketing.dto.MetricsResponse;
import com.inyoung.ticketing.service.MetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 메인 대시보드 지표 API
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {
	private final MetricsService metricsService;

	public MetricsController(MetricsService metricsService) {
		this.metricsService = metricsService;
	}

	@GetMapping
	public MetricsResponse getMetrics() {
		return metricsService.getMetrics();
	}
}
