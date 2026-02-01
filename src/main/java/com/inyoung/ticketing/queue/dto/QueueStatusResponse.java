package com.inyoung.ticketing.queue.dto;

// 대기열 상태 조회 응답 DTO
public class QueueStatusResponse {
	private String token;
	private Long rank;
	private Long totalWaiting;
	private Boolean isAllowed;

	// 토큰, 순번, 대기인원 수, 입장 허용 여부를 담아 응답 생성
	public QueueStatusResponse(String token, Long rank, Long totalWaiting, Boolean isAllowed) {
		this.token = token;
		this.rank = rank;
		this.totalWaiting = totalWaiting;
		this.isAllowed = isAllowed;
	}

	// 대기열 토큰
	public String getToken() {
		return token;
	}

	// 대기 순번
	public Long getRank() {
		return rank;
	}

	// 전체 대기인원 수
	public Long getTotalWaiting() {
		return totalWaiting;
	}

	// 입장 허용 여부
	public Boolean getIsAllowed() {
		return isAllowed;
	}
}
