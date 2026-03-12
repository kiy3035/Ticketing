package com.inyoung.ticketing.admin.dto;

import java.time.Instant;

/**
 * 마감된 공연별 미판매 좌석 통계 한 행.
 * Admin 전용 통계 API 응답용.
 */
public class UnsoldSeatSummaryItem {
	private Long concertId;
	private String title;
	private String venue;
	private Instant concertAt;
	private long totalSeats;
	private long soldSeats;
	private long unsoldSeats;

	public UnsoldSeatSummaryItem(
		Long concertId,
		String title,
		String venue,
		Instant concertAt,
		long totalSeats,
		long soldSeats,
		long unsoldSeats
	) {
		this.concertId = concertId;
		this.title = title;
		this.venue = venue;
		this.concertAt = concertAt;
		this.totalSeats = totalSeats;
		this.soldSeats = soldSeats;
		this.unsoldSeats = unsoldSeats;
	}

	public Long getConcertId() {
		return concertId;
	}

	public String getTitle() {
		return title;
	}

	public String getVenue() {
		return venue;
	}

	public Instant getConcertAt() {
		return concertAt;
	}

	public long getTotalSeats() {
		return totalSeats;
	}

	public long getSoldSeats() {
		return soldSeats;
	}

	public long getUnsoldSeats() {
		return unsoldSeats;
	}
}
