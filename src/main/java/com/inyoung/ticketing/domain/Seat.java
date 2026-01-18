package com.inyoung.ticketing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

// 콘서트 내 좌석 엔티티
@Entity
@Table(
	name = "seat",
	uniqueConstraints = {
		@UniqueConstraint(columnNames = { "concert_id", "section", "seat_no" })
	}
)
public class Seat {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "concert_id", nullable = false)
	private Concert concert;

	@Column(nullable = false, length = 50)
	private String section;

	@Column(name = "seat_no", nullable = false, length = 20)
	private String seatNo;

	@Column(nullable = false)
	private Long price;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private SeatStatus status = SeatStatus.AVAILABLE;

	// 식별자
	public Long getId() {
		return id;
	}

	// 소속 콘서트
	public Concert getConcert() {
		return concert;
	}

	// 소속 콘서트 설정
	public void setConcert(Concert concert) {
		this.concert = concert;
	}

	// 구역
	public String getSection() {
		return section;
	}

	// 구역 설정
	public void setSection(String section) {
		this.section = section;
	}

	// 좌석 번호
	public String getSeatNo() {
		return seatNo;
	}

	// 좌석 번호 설정
	public void setSeatNo(String seatNo) {
		this.seatNo = seatNo;
	}

	// 가격
	public Long getPrice() {
		return price;
	}

	// 가격 설정
	public void setPrice(Long price) {
		this.price = price;
	}

	// 좌석 상태
	public SeatStatus getStatus() {
		return status;
	}

	// 좌석 상태 설정
	public void setStatus(SeatStatus status) {
		this.status = status;
	}
}
