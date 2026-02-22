package com.inyoung.ticketing.scheduler;

import java.util.List;
import java.util.Optional;
import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.concert.domain.Concert;
import com.inyoung.ticketing.concert.repository.ConcertRepository;
import com.inyoung.ticketing.queue.service.QueueService;
import com.inyoung.ticketing.seat.domain.SeatStatus;
import com.inyoung.ticketing.seat.repository.SeatRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 대기열에서 순번이 된 사용자들을 입장 허용하는 스케줄러
// 2초마다 상위 50명 일괄 처리. 좌석 수가 50보다 적으면 예매 가능 좌석 수만큼만 입장 허용.
@Component
public class QueueProcessingScheduler {
	private final QueueService queueService;
	private final ConcertRepository concertRepository;
	private final SeatRepository seatRepository;
	private final TicketingProperties properties;

	// 서비스 및 리포지토리 주입
	public QueueProcessingScheduler(
		QueueService queueService,
		ConcertRepository concertRepository,
		SeatRepository seatRepository,
		TicketingProperties properties
	) {
		this.queueService = queueService;
		this.concertRepository = concertRepository;
		this.seatRepository = seatRepository;
		this.properties = properties;
	}

	/**
	 * 2초마다 각 콘서트별 대기열 상위 N명을 입장 허용.
	 * 좌석 개수가 배치 크기(50)보다 적으면 예매 가능 좌석 수만큼만 입장 제한하여 허용.
	 */
	@Scheduled(fixedDelayString = "${ticketing.queue.processing-interval-ms:2000}")
	public void processQueue() {
		int batchSize = properties.getQueue().getBatchSize();

		// 모든 콘서트 조회
		List<Concert> concerts = concertRepository.findAll();

		for (Concert concert : concerts) {
			Long concertId = concert.getId();

			// 예매 가능 좌석 수 = 전체 - 이미 예매 완료(RESERVED). 이 수를 초과하여 입장 허용하지 않음.
			long totalSeats = seatRepository.countByConcertId(concertId);
			long reservedCount = seatRepository.countByConcertIdAndStatus(concertId, SeatStatus.RESERVED);
			long availableSeats = Math.max(0, totalSeats - reservedCount);
			int allowCount = (int) Math.min(batchSize, availableSeats);

			// 대기열에서 상위 N명 조회 (배치 크기만큼)
			List<String> topTokens = queueService.getTopTokens(concertId, batchSize);

			int allowed = 0;
			for (String token : topTokens) {
				if (allowed >= allowCount) {
					break; // 입장 허용 한도 도달
				}
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
				allowed++;
			}
		}
	}
}
