package com.inyoung.ticketing.dto;

import java.time.Instant;
import com.inyoung.ticketing.domain.Concert;
import com.inyoung.ticketing.domain.ConcertStatus;

public class ConcertResponse {
	private Long id;
	private String title;
	private String venue;
	private Instant startAt;
	private Instant endAt;
	private ConcertStatus status;

	public ConcertResponse(Concert concert) {
		this.id = concert.getId();
		this.title = concert.getTitle();
		this.venue = concert.getVenue();
		this.startAt = concert.getStartAt();
		this.endAt = concert.getEndAt();
		this.status = concert.getStatus();
	}

	public Long getId() {
		return id;
	}

	public String getTitle() {
		return title;
	}

	public String getVenue() {
		return venue;
	}

	public Instant getStartAt() {
		return startAt;
	}

	public Instant getEndAt() {
		return endAt;
	}

	public ConcertStatus getStatus() {
		return status;
	}
}
