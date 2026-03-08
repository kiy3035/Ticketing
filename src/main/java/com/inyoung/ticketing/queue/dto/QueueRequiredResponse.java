package com.inyoung.ticketing.queue.dto;

/**
 * 대기열 필요 여부 조회 응답 (패턴 B: 임계치 초과 시에만 대기열 활성화)
 */
public class QueueRequiredResponse {
	private final boolean required;

	public QueueRequiredResponse(boolean required) {
		this.required = required;
	}

	public boolean isRequired() {
		return required;
	}
}
