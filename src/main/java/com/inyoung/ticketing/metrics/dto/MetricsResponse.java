package com.inyoung.ticketing.metrics.dto;

// 메인 대시보드 지표 응답 DTO
public class MetricsResponse {
	private long activeUsers;
	private long todayOpen;
	private double successRate;

	public MetricsResponse(long activeUsers, long todayOpen, double successRate) {
		this.activeUsers = activeUsers;
		this.todayOpen = todayOpen;
		this.successRate = successRate;
	}

	public long getActiveUsers() {
		return activeUsers;
	}

	public long getTodayOpen() {
		return todayOpen;
	}

	public double getSuccessRate() {
		return successRate;
	}
}
