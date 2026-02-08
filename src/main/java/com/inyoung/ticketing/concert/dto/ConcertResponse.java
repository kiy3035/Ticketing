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
	private Instant startAt;
	private Instant endAt;
	private ConcertStatus status;
	private ConcertCategory category;

	// 엔티티에서 응답 DTO로 변환
	// 상태는 현재 시간 기준으로 동적 계산
	public ConcertResponse(Concert concert) {
		this.id = concert.getId();
		this.title = concert.getTitle();
		this.venue = concert.getVenue();
		this.startAt = concert.getStartAt();
		this.endAt = concert.getEndAt();
		this.status = calculateStatus(concert);
		this.category = concert.getCategory();
	}

	/**
	 * 현재 시간 기준으로 콘서트 상태 계산
	 * 
	 * CANCELLED → CANCELLED (명시적 취소)
	 * 현재 < startAt → UPCOMING (예정)
	 * startAt ≤ 현재 < endAt → ONGOING (진행중)
	 * 현재 ≥ endAt → COMPLETED (완료)
	 */
	private ConcertStatus calculateStatus(Concert concert) {
		// 명시적 취소는 유지
		if (concert.getStatus() == ConcertStatus.CANCELLED) {
			return ConcertStatus.CANCELLED;
		}

		Instant now = Instant.now();
		Instant startAt = concert.getStartAt();
		Instant endAt = concert.getEndAt();

		if (now.isBefore(startAt)) {
			return ConcertStatus.UPCOMING;
		} else if (now.isBefore(endAt)) {
			return ConcertStatus.ONGOING;
		} else {
			return ConcertStatus.COMPLETED;
		}
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

	// 시작 시각
	public Instant getStartAt() {
		return startAt;
	}

	// 종료 시각
	public Instant getEndAt() {
		return endAt;
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
