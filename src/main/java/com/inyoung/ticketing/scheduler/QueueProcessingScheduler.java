package com.inyoung.ticketing.scheduler;

import java.util.List;
import java.util.Optional;
import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.concert.domain.Concert;
import com.inyoung.ticketing.concert.repository.ConcertRepository;
import com.inyoung.ticketing.queue.service.QueueService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 대기열에서 순번이 된 사용자들을 입장 허용하는 스케줄러
@Component
public class QueueProcessingScheduler {
	private final QueueService queueService;
	private final ConcertRepository concertRepository;
	private final TicketingProperties properties;

	// 서비스 및 리포지토리 주입
	public QueueProcessingScheduler(
		QueueService queueService,
		ConcertRepository concertRepository,
		TicketingProperties properties
	) {
		this.queueService = queueService;
		this.concertRepository = concertRepository;
		this.properties = properties;
	}

	@Scheduled(fixedDelayString = "${ticketing.queue.processing-interval-ms:2000}")
	// 주기적으로 각 콘서트별 대기열을 스캔하여 상위 N명을 입장 허용한다.
	// - 각 콘서트별로 대기열에서 상위 N명(배치 크기)을 조회한다.
	// - 이미 입장 허용된 사용자는 제외한다.
	// - 입장 허용 상태를 설정하여 프론트엔드에서 감지할 수 있게 한다.
	public void processQueue() {
		int batchSize = properties.getQueue().getBatchSize();
		
		// 모든 콘서트 조회
		List<Concert> concerts = concertRepository.findAll();
		
		for (Concert concert : concerts) {
			Long concertId = concert.getId();
			
			// 대기열에서 상위 N명 조회
			List<String> topTokens = queueService.getTopTokens(concertId, batchSize);
			
			for (String token : topTokens) {
				// 이미 입장 허용되었는지 확인
				Optional<Long> allowedConcertId = queueService.isAllowed(token);
				if (allowedConcertId.isPresent()) {
					continue; // 이미 허용됨
				}
				
				// 토큰 정보 확인
				Optional<QueueService.QueueTokenData> tokenData = queueService.getTokenData(token);
				if (tokenData.isEmpty()) {
					continue; // 토큰이 없음
				}
				
				// 콘서트 ID 일치 확인
				if (!tokenData.get().getConcertId().equals(concertId)) {
					continue; // 다른 콘서트 토큰
				}
				
				// 입장 허용 상태 설정
				queueService.allowEntry(token, concertId);
			}
		}
	}
}
