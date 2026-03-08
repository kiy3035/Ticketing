package com.inyoung.ticketing.metrics;

import com.inyoung.ticketing.concert.domain.Concert;
import com.inyoung.ticketing.concert.repository.ConcertRepository;
import com.inyoung.ticketing.queue.service.QueueService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * Prometheus용 대기열 메트릭. 콘서트별 현재 대기 인원 수를 Gauge로 노출.
 * 메트릭명: ticketing_queue_waiting_count, 태그: concert_id
 */
@Component
public class QueueMetrics implements MeterBinder {
	private final ConcertRepository concertRepository;
	private final QueueService queueService;

	public QueueMetrics(ConcertRepository concertRepository, QueueService queueService) {
		this.concertRepository = concertRepository;
		this.queueService = queueService;
	}

	@Override
	public void bindTo(MeterRegistry registry) {
		for (Concert concert : concertRepository.findAll()) {
			Long concertId = concert.getId();
			Gauge.builder("ticketing_queue_waiting_count", () -> queueService.countWaiting(concertId))
				.tag("concert_id", String.valueOf(concertId))
				.description("Current number of users waiting in queue for this concert")
				.register(registry);
		}
	}
}
