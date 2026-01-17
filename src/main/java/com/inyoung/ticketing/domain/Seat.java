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

	public Long getId() {
		return id;
	}

	public Concert getConcert() {
		return concert;
	}

	public void setConcert(Concert concert) {
		this.concert = concert;
	}

	public String getSection() {
		return section;
	}

	public void setSection(String section) {
		this.section = section;
	}

	public String getSeatNo() {
		return seatNo;
	}

	public void setSeatNo(String seatNo) {
		this.seatNo = seatNo;
	}

	public Long getPrice() {
		return price;
	}

	public void setPrice(Long price) {
		this.price = price;
	}

	public SeatStatus getStatus() {
		return status;
	}

	public void setStatus(SeatStatus status) {
		this.status = status;
	}
}
