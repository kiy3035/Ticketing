package com.inyoung.ticketing.metrics.dto;

// 메인 대시보드 지표 응답 DTO
public class MetricsResponse {
	private long activeUsers;
	private long todayOpen;
	private long upcomingOpen;

	public MetricsResponse(long activeUsers, long todayOpen, long upcomingOpen) {
		this.activeUsers = activeUsers;
		this.todayOpen = todayOpen;
		this.upcomingOpen = upcomingOpen;
	}

	public long getActiveUsers() {
		return activeUsers;
	}

	public long getTodayOpen() {
		return todayOpen;
	}

	public long getUpcomingOpen() {
		return upcomingOpen;
	}
}
