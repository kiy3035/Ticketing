package com.inyoung.ticketing.seller.dto;

import com.inyoung.ticketing.concert.domain.ConcertCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public class SellerConcertCreateRequest {
	@NotBlank(message = "제목을 입력하세요")
	private String title;
	@NotBlank(message = "장소를 입력하세요")
	private String venue;
	@NotNull(message = "공연 일시를 입력하세요")
	private Instant concertAt;
	@NotNull(message = "카테고리를 선택하세요")
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
