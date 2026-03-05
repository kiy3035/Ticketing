package com.inyoung.ticketing.seller.dto;

import com.inyoung.ticketing.concert.domain.ConcertCategory;
import java.time.Instant;

public class SellerConcertUpdateRequest {
	private String title;
	private String venue;
	private Instant startAt;
	private Instant endAt;
	private ConcertCategory category;

	public String getTitle() { return title; }
	public void setTitle(String title) { this.title = title; }
	public String getVenue() { return venue; }
	public void setVenue(String venue) { this.venue = venue; }
	public Instant getStartAt() { return startAt; }
	public void setStartAt(Instant startAt) { this.startAt = startAt; }
	public Instant getEndAt() { return endAt; }
	public void setEndAt(Instant endAt) { this.endAt = endAt; }
	public ConcertCategory getCategory() { return category; }
	public void setCategory(ConcertCategory category) { this.category = category; }
}
