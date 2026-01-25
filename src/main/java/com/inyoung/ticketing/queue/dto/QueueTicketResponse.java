package com.inyoung.ticketing.queue.dto;

// 대기열 토큰 발급 응답 DTO
public class QueueTicketResponse {
	private String token;
	private Long issuedAtEpochMs;

	// 토큰과 발급 시각을 담아 응답 생성
	public QueueTicketResponse(String token, Long issuedAtEpochMs) {
		this.token = token;
		this.issuedAtEpochMs = issuedAtEpochMs;
	}

	// 대기열 토큰
	public String getToken() {
		return token;
	}

	// 발급 시각(에포크 밀리초)
	public Long getIssuedAtEpochMs() {
		return issuedAtEpochMs;
	}
}
