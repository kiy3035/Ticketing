package com.inyoung.ticketing.metrics.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import com.inyoung.ticketing.concert.repository.ConcertRepository;
import com.inyoung.ticketing.metrics.dto.MetricsResponse;
import org.springframework.stereotype.Service;

// 메인 대시보드 지표 계산 서비스
@Service
public class MetricsService {
	private final ConcertRepository concertRepository;
	private final ActiveUserTracker activeUserTracker;

	public MetricsService(ConcertRepository concertRepository, ActiveUserTracker activeUserTracker) {
		this.concertRepository = concertRepository;
		this.activeUserTracker = activeUserTracker;
	}

	public MetricsResponse getMetrics() {
		long activeUsers = activeUserTracker.countActive();

		LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
		Instant todayStart = today.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
		Instant todayEnd = today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
		Instant upcomingEnd = today.plusDays(7).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();

		long todayOpen = concertRepository.countByConcertAtBetween(todayStart, todayEnd);
		long upcomingOpen = concertRepository.countByConcertAtBetween(todayStart, upcomingEnd);

		return new MetricsResponse(activeUsers, todayOpen, upcomingOpen);
	}
}
