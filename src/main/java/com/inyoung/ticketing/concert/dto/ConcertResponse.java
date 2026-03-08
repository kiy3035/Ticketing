package com.inyoung.ticketing.concert.dto;

import java.time.Instant;
import com.inyoung.ticketing.concert.domain.Concert;
import com.inyoung.ticketing.concert.domain.ConcertCategory;
import com.inyoung.ticketing.concert.domain.ConcertStatus;

// 콘서트 응답 DTO
public class ConcertResponse {
	private Long id;
	private String title;
	private String venue;
	private Instant concertAt;
	private ConcertStatus status;
	private ConcertCategory category;

	// 엔티티에서 응답 DTO로 변환
	// 상태는 현재 시간 기준으로 동적 계산
	public ConcertResponse(Concert concert) {
		this.id = concert.getId();
		this.title = concert.getTitle();
		this.venue = concert.getVenue();
		this.concertAt = concert.getConcertAt();
		this.status = calculateStatus(concert);
		this.category = concert.getCategory();
	}

	/**
	 * 현재 시간 기준으로 콘서트 상태 계산
	 * CANCELLED → CANCELLED (명시적 취소)
	 * 현재 < concertAt → UPCOMING (예정)
	 * 현재 ≥ concertAt → COMPLETED (완료)
	 */
	private ConcertStatus calculateStatus(Concert concert) {
		if (concert.getStatus() == ConcertStatus.CANCELLED) {
			return ConcertStatus.CANCELLED;
		}
		Instant now = Instant.now();
		return now.isBefore(concert.getConcertAt()) ? ConcertStatus.UPCOMING : ConcertStatus.COMPLETED;
	}

	// 콘서트 ID
	public Long getId() {
		return id;
	}

	// 콘서트 제목
	public String getTitle() {
		return title;
	}

	// 공연장
	public String getVenue() {
		return venue;
	}

	// 공연 일시
	public Instant getConcertAt() {
		return concertAt;
	}

	// 진행 상태
	public ConcertStatus getStatus() {
		return status;
	}

	// 카테고리
	public ConcertCategory getCategory() {
		return category;
	}
}
