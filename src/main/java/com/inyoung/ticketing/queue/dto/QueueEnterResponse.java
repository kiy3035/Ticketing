package com.inyoung.ticketing.queue.dto;

// 대기열 진입 응답 DTO
public class QueueEnterResponse {
	private String token;
	private Long rank;
	private Long totalWaiting;

	// 토큰, 순번, 대기인원 수를 담아 응답 생성
	public QueueEnterResponse(String token, Long rank, Long totalWaiting) {
		this.token = token;
		this.rank = rank;
		this.totalWaiting = totalWaiting;
	}

	// 대기열 토큰
	public String getToken() {
		return token;
	}

	// 현재 순번
	public Long getRank() {
		return rank;
	}

	// 전체 대기인원 수
	public Long getTotalWaiting() {
		return totalWaiting;
	}
}
