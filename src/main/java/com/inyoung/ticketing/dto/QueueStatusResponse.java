package com.inyoung.ticketing.dto;

// 대기열 상태 조회 응답 DTO
public class QueueStatusResponse {
	private String token;
	private Long rank;

	// 토큰과 순번을 담아 응답 생성
	public QueueStatusResponse(String token, Long rank) {
		this.token = token;
		this.rank = rank;
	}

	// 대기열 토큰
	public String getToken() {
		return token;
	}

	// 대기 순번
	public Long getRank() {
		return rank;
	}
}
