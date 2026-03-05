package com.inyoung.ticketing.seller.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import com.inyoung.ticketing.concert.domain.ConcertCategory;
import com.inyoung.ticketing.concert.domain.ConcertStatus;

/**
 * 판매자 대시보드용 콘서트 응답 DTO (좌석 수, 예매 수, 매출 포함)
 */
public class SellerConcertResponse {
	private Long id;
	private String title;
	private String venue;
	private Instant startAt;
	private Instant endAt;
	private ConcertStatus status;
	private ConcertCategory category;
	private OffsetDateTime createdAt;
	private int seatCount;
	private long reservedCount;
	private Long totalRevenue;

	public SellerConcertResponse() {
	}

	public SellerConcertResponse(Long id, String title, String venue, Instant startAt, Instant endAt,
		ConcertStatus status, ConcertCategory category, OffsetDateTime createdAt,
		int seatCount, long reservedCount, Long totalRevenue) {
		this.id = id;
		this.title = title;
		this.venue = venue;
		this.startAt = startAt;
		this.endAt = endAt;
		this.status = status;
		this.category = category;
		this.createdAt = createdAt;
		this.seatCount = seatCount;
		this.reservedCount = reservedCount;
		this.totalRevenue = totalRevenue != null ? totalRevenue : 0L;
	}

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public String getTitle() { return title; }
	public void setTitle(String title) { this.title = title; }
	public String getVenue() { return venue; }
	public void setVenue(String venue) { this.venue = venue; }
	public Instant getStartAt() { return startAt; }
	public void setStartAt(Instant startAt) { this.startAt = startAt; }
	public Instant getEndAt() { return endAt; }
	public void setEndAt(Instant endAt) { this.endAt = endAt; }
	public ConcertStatus getStatus() { return status; }
	public void setStatus(ConcertStatus status) { this.status = status; }
	public ConcertCategory getCategory() { return category; }
	public void setCategory(ConcertCategory category) { this.category = category; }
	public OffsetDateTime getCreatedAt() { return createdAt; }
	public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
	public int getSeatCount() { return seatCount; }
	public void setSeatCount(int seatCount) { this.seatCount = seatCount; }
	public long getReservedCount() { return reservedCount; }
	public void setReservedCount(long reservedCount) { this.reservedCount = reservedCount; }
	public Long getTotalRevenue() { return totalRevenue; }
	public void setTotalRevenue(Long totalRevenue) { this.totalRevenue = totalRevenue != null ? totalRevenue : 0L; }
}
