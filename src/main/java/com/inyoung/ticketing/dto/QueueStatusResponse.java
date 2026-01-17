package com.inyoung.ticketing.dto;

public class QueueStatusResponse {
	private String token;
	private Long rank;

	public QueueStatusResponse(String token, Long rank) {
		this.token = token;
		this.rank = rank;
	}

	public String getToken() {
		return token;
	}

	public Long getRank() {
		return rank;
	}
}
