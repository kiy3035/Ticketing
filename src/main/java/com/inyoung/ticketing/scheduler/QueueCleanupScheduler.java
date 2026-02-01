package com.inyoung.ticketing.scheduler;

import java.util.List;
import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.concert.domain.Concert;
import com.inyoung.ticketing.concert.repository.ConcertRepository;
import com.inyoung.ticketing.queue.service.QueueService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 대기열에서 만료된 토큰을 정리하는 스케줄러
@Component
public class QueueCleanupScheduler {
	private final QueueService queueService;
	private final ConcertRepository concertRepository;
	private final TicketingProperties properties;

	public QueueCleanupScheduler(
		QueueService queueService,
		ConcertRepository concertRepository,
		TicketingProperties properties
	) {
		this.queueService = queueService;
		this.concertRepository = concertRepository;
		this.properties = properties;
	}

	@Scheduled(fixedDelayString = "${ticketing.queue.cleanup-interval-ms:60000}")
	// 주기적으로 각 콘서트별 대기열에서 만료된 토큰을 제거한다.
	public void cleanupExpiredQueueTokens() {
		int batchSize = properties.getQueue().getCleanupBatchSize();
		List<Concert> concerts = concertRepository.findAll();
		for (Concert concert : concerts) {
			queueService.pruneExpiredTokens(concert.getId(), batchSize);
		}
	}
}
