package com.inyoung.ticketing.hold.dto;

import java.time.Instant;

/**
 * 사용자별 "예약 중" 홀드 목록용 응답 DTO (공연·좌석 정보 포함)
 */
public class HoldItemResponse {
	private String holdToken;
	private Long concertId;
	private String concertTitle;
	private String venue;
	private Instant concertAt;
	private Long seatId;
	private String section;
	private String seatNo;
	private Long price;
	private Instant expiresAt;

	public HoldItemResponse() {
	}

	public HoldItemResponse(String holdToken, Long concertId, String concertTitle, String venue,
		Instant concertAt, Long seatId, String section, String seatNo, Long price, Instant expiresAt) {
		this.holdToken = holdToken;
		this.concertId = concertId;
		this.concertTitle = concertTitle;
		this.venue = venue;
		this.concertAt = concertAt;
		this.seatId = seatId;
		this.section = section;
		this.seatNo = seatNo;
		this.price = price;
		this.expiresAt = expiresAt;
	}

	public String getHoldToken() { return holdToken; }
	public void setHoldToken(String holdToken) { this.holdToken = holdToken; }
	public Long getConcertId() { return concertId; }
	public void setConcertId(Long concertId) { this.concertId = concertId; }
	public String getConcertTitle() { return concertTitle; }
	public void setConcertTitle(String concertTitle) { this.concertTitle = concertTitle; }
	public String getVenue() { return venue; }
	public void setVenue(String venue) { this.venue = venue; }
	public Instant getConcertAt() { return concertAt; }
	public void setConcertAt(Instant concertAt) { this.concertAt = concertAt; }
	public Long getSeatId() { return seatId; }
	public void setSeatId(Long seatId) { this.seatId = seatId; }
	public String getSection() { return section; }
	public void setSection(String section) { this.section = section; }
	public String getSeatNo() { return seatNo; }
	public void setSeatNo(String seatNo) { this.seatNo = seatNo; }
	public Long getPrice() { return price; }
	public void setPrice(Long price) { this.price = price; }
	public Instant getExpiresAt() { return expiresAt; }
	public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
