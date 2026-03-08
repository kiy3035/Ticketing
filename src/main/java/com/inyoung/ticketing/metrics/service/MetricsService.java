package com.inyoung.ticketing.metrics.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import com.inyoung.ticketing.concert.repository.ConcertRepository;
import com.inyoung.ticketing.metrics.dto.MetricsResponse;
import com.inyoung.ticketing.reservation.domain.ReservationStatus;
import com.inyoung.ticketing.reservation.repository.ReservationRepository;
import org.springframework.stereotype.Service;

// 메인 대시보드 지표 계산 서비스
@Service
public class MetricsService {
	private final ConcertRepository concertRepository;
	private final ReservationRepository reservationRepository;
	private final ActiveUserTracker activeUserTracker;

	public MetricsService(
		ConcertRepository concertRepository,
		ReservationRepository reservationRepository,
		ActiveUserTracker activeUserTracker
	) {
		this.concertRepository = concertRepository;
		this.reservationRepository = reservationRepository;
		this.activeUserTracker = activeUserTracker;
	}

	public MetricsResponse getMetrics() {
		long activeUsers = activeUserTracker.countActive();

		LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
		Instant start = today.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
		Instant end = today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
		long todayOpen = concertRepository.countByConcertAtBetween(start, end);

		long confirmed = reservationRepository.countByStatus(ReservationStatus.CONFIRMED);
		long cancelled = reservationRepository.countByStatus(ReservationStatus.CANCELLED);
		long total = confirmed + cancelled;
		double successRate = total == 0 ? 0.0 : (confirmed * 100.0) / total;

		return new MetricsResponse(activeUsers, todayOpen, successRate);
	}
}
