package com.inyoung.ticketing.seller.dto;

import com.inyoung.ticketing.concert.domain.ConcertCategory;
import java.time.Instant;

public class SellerConcertUpdateRequest {
	private String title;
	private String venue;
	private Instant concertAt;
	private ConcertCategory category;

	public String getTitle() { return title; }
	public void setTitle(String title) { this.title = title; }
	public String getVenue() { return venue; }
	public void setVenue(String venue) { this.venue = venue; }
	public Instant getConcertAt() { return concertAt; }
	public void setConcertAt(Instant concertAt) { this.concertAt = concertAt; }
	public ConcertCategory getCategory() { return category; }
	public void setCategory(ConcertCategory category) { this.category = category; }
}
