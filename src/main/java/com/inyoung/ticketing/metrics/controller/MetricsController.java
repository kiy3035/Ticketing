package com.inyoung.ticketing.metrics.controller;

import com.inyoung.ticketing.metrics.dto.MetricsResponse;
import com.inyoung.ticketing.metrics.service.MetricsService;
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
