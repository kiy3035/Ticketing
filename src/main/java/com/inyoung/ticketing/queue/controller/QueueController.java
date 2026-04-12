package com.inyoung.ticketing.queue.controller;

import java.util.Optional;
import com.inyoung.ticketing.common.api.ApiResponse;
import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.queue.dto.QueueAllowedResponse;
import com.inyoung.ticketing.queue.dto.QueueEnterResponse;
import com.inyoung.ticketing.queue.dto.QueueRequiredResponse;
import com.inyoung.ticketing.queue.dto.QueueStatusResponse;
import com.inyoung.ticketing.queue.dto.QueueTicketResponse;
import com.inyoung.ticketing.queue.service.QueueService;
import com.inyoung.ticketing.seat.service.SeatService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 대기열 API 컨트롤러.
 * 좌석 집계(예매 가능 수)는 {@link SeatService} 에 위임한다 — 컨트롤러가 JPA Repository 를 직접 쓰지 않게 해
 * 테스트·ArchUnit 규칙( controller → repository 의존 금지 )을 지키기 위함이다.
 */
@RestController
@Validated
@RequestMapping("/api/queue")
public class QueueController {
	private final QueueService queueService;
	private final SeatService seatService;
	private final TicketingProperties properties;

	public QueueController(QueueService queueService, SeatService seatService, TicketingProperties properties) {
		this.queueService = queueService;
		this.seatService = seatService;
		this.properties = properties;
	}

	// 콘서트 대기열 진입 (토큰 발급). 대기 인원이 적고 좌석이 있으면 즉시 입장 허용.
	@PostMapping("/enter")
	@ResponseStatus(HttpStatus.CREATED)
	public QueueEnterResponse enter(
		Authentication authentication,
		@RequestParam @NotNull Long concertId
	) {
		// 부하 테스트용: 인증이 없으면 테스트용 userId 생성
		String userId = authentication != null 
			? authentication.getName() 
			: "test-user-" + System.currentTimeMillis();
		QueueService.QueueTokenInfo tokenInfo = queueService.enterQueue(concertId, userId);

		boolean immediatelyAllowed = false;
		int threshold = properties.getQueue().getImmediateAllowThreshold();
		if (threshold > 0 && tokenInfo.getTotalWaiting() <= threshold) {
			// Redis 홀드 + DB RESERVED 를 반영한 "남은 좌석" (SeatService 에 캡슐화)
			long availableSeats = seatService.countAvailableSeatsForDecision(concertId);
			if (tokenInfo.getTotalWaiting() <= availableSeats) {
				queueService.allowEntry(tokenInfo.getToken(), concertId);
				immediatelyAllowed = true;
			}
		}

		return new QueueEnterResponse(
			tokenInfo.getToken(),
			tokenInfo.getRank(),
			tokenInfo.getTotalWaiting(),
			immediatelyAllowed
		);
	}

	// 대기 순번 조회
	@GetMapping("/status")
	public QueueStatusResponse status(
		@RequestParam @NotBlank String token,
		@RequestParam @NotNull Long concertId
	) {
		Long rank = queueService.getRank(concertId, token);
		if (rank == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Token not found");
		}
		Long totalWaiting = queueService.countWaiting(concertId);
		Optional<Long> allowedConcertId = queueService.isAllowed(token);
		Boolean isAllowed = allowedConcertId.isPresent() && allowedConcertId.get().equals(concertId);
		// 프론트 대기열 화면에 표시할 남은 좌석 수
		long availableSeats = seatService.countAvailableSeatsForQueueStatus(concertId);

		// 예상 대기 시간 = ceil(순번 / 분당입장인원)
		// 분당입장인원 = batchSize × (60_000 / processingIntervalMs)
		int batchSize = properties.getQueue().getBatchSize();
		long intervalMs = properties.getQueue().getProcessingIntervalMs();
		double perMinute = batchSize * (60_000.0 / intervalMs);
		long estimatedWaitMinutes = Math.max(1L, (long) Math.ceil(rank / perMinute));

		return new QueueStatusResponse(token, rank, totalWaiting, isAllowed, availableSeats, estimatedWaitMinutes);
	}

	// 입장 허용 여부 확인
	@GetMapping("/allowed")
	public QueueAllowedResponse allowed(@RequestParam @NotBlank String token) {
		Optional<Long> allowedConcertId = queueService.isAllowed(token);
		return new QueueAllowedResponse(allowedConcertId.isPresent(), allowedConcertId.orElse(null));
	}

	/**
	 * 대기열 필요 여부 (패턴 B). 대기 인원이 activation-threshold 초과 시에만 required=true.
	 * required=false면 queue 페이지 없이 바로 좌석 페이지 진입 가능.
	 */
	@GetMapping("/required")
	public QueueRequiredResponse required(@RequestParam @NotNull Long concertId) {
		long waiting = queueService.countWaiting(concertId);
		int threshold = properties.getQueue().getActivationThreshold();
		boolean required = threshold > 0 && waiting > threshold;
		return new QueueRequiredResponse(required);
	}

	// 콘서트별 대기인원 수 조회 (원시 long 반환 금지: ApiResponseAdvice 래핑 후 메시지 컨버터 타입 불일치로 ClassCastException 발생)
	@GetMapping("/count")
	public ApiResponse<Long> count(@RequestParam @NotNull Long concertId) {
		return ApiResponse.success(queueService.countWaiting(concertId));
	}

	// 대기열에서 나가기
	@DeleteMapping("/exit")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void exit(
		@RequestParam @NotBlank String token,
		@RequestParam @NotNull Long concertId
	) {
		queueService.exitQueue(concertId, token);
	}

	// 대기열 토큰 발급 (기존 API - 하위 호환성 유지)
	@GetMapping("/ticket")
	@ResponseStatus(HttpStatus.CREATED)
	public QueueTicketResponse issueTicket(@RequestParam @NotBlank String userId) {
		// 기존 API는 전역 대기열을 사용하지만, 이제는 사용하지 않음
		// 하위 호환성을 위해 유지하되, 콘서트별 대기열 사용을 권장
		throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please use POST /api/queue/enter?concertId={id} instead");
	}
}
