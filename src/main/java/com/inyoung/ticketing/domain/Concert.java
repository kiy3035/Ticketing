package com.inyoung.ticketing.domain;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "concert")
public class Concert {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(nullable = false, length = 200)
	private String venue;

	@Column(nullable = false)
	private Instant startAt;

	@Column(nullable = false)
	private Instant endAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ConcertStatus status = ConcertStatus.UPCOMING;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@PrePersist
	void prePersist() {
		this.createdAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getVenue() {
		return venue;
	}

	public void setVenue(String venue) {
		this.venue = venue;
	}

	public Instant getStartAt() {
		return startAt;
	}

	public void setStartAt(Instant startAt) {
		this.startAt = startAt;
	}

	public Instant getEndAt() {
		return endAt;
	}

	public void setEndAt(Instant endAt) {
		this.endAt = endAt;
	}

	public ConcertStatus getStatus() {
		return status;
	}

	public void setStatus(ConcertStatus status) {
		this.status = status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
