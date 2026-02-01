package com.inyoung.ticketing.queue.dto;

// 대기열 입장 허용 여부 확인 응답 DTO
public class QueueAllowedResponse {
	private boolean allowed;
	private Long concertId;

	// 입장 허용 여부와 콘서트 ID를 담아 응답 생성
	public QueueAllowedResponse(boolean allowed, Long concertId) {
		this.allowed = allowed;
		this.concertId = concertId;
	}

	// 입장 허용 여부
	public boolean isAllowed() {
		return allowed;
	}

	// 콘서트 ID
	public Long getConcertId() {
		return concertId;
	}
}
