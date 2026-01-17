package com.inyoung.ticketing.dto;

public class QueueTicketResponse {
	private String token;
	private Long issuedAtEpochMs;

	public QueueTicketResponse(String token, Long issuedAtEpochMs) {
		this.token = token;
		this.issuedAtEpochMs = issuedAtEpochMs;
	}

	public String getToken() {
		return token;
	}

	public Long getIssuedAtEpochMs() {
		return issuedAtEpochMs;
	}
}
