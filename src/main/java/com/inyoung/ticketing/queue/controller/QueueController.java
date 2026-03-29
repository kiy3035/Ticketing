package com.inyoung.ticketing.queue.controller;

import java.util.List;
import java.util.Optional;
import com.inyoung.ticketing.common.api.ApiResponse;
import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.hold.store.HoldStore;
import com.inyoung.ticketing.queue.dto.QueueAllowedResponse;
import com.inyoung.ticketing.queue.dto.QueueEnterResponse;
import com.inyoung.ticketing.queue.dto.QueueRequiredResponse;
import com.inyoung.ticketing.queue.dto.QueueStatusResponse;
import com.inyoung.ticketing.queue.dto.QueueTicketResponse;
import com.inyoung.ticketing.queue.service.QueueService;
import com.inyoung.ticketing.seat.domain.SeatStatus;
import com.inyoung.ticketing.seat.repository.SeatRepository;
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

// 대기열 API 컨트롤러
@RestController
@Validated
@RequestMapping("/api/queue")
public class QueueController {
	private final QueueService queueService;
	private final SeatRepository seatRepository;
	private final HoldStore holdStore;
	private final TicketingProperties properties;

	public QueueController(QueueService queueService, SeatRepository seatRepository, HoldStore holdStore, TicketingProperties properties) {
		this.queueService = queueService;
		this.seatRepository = seatRepository;
		this.holdStore = holdStore;
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
			long totalSeats = seatRepository.countByConcertId(concertId);
			long reserved = seatRepository.countByConcertIdAndStatus(concertId, SeatStatus.RESERVED);
			List<Long> seatIds = seatRepository.findSeatIdsByConcertId(concertId);
			int heldCount = holdStore.findHeldSeatIds(seatIds).size();
			long availableSeats = Math.max(0, totalSeats - reserved - heldCount);
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
		long totalSeats = seatRepository.countByConcertId(concertId);
		long reserved = seatRepository.countByConcertIdAndStatus(concertId, SeatStatus.RESERVED);
		List<Long> seatIds = seatRepository.findSeatIdsByConcertId(concertId);
		int heldCount = holdStore.findHeldSeatIds(seatIds).size();
		long availableSeats = Math.max(0, totalSeats - reserved - heldCount);
		return new QueueStatusResponse(token, rank, totalWaiting, isAllowed, availableSeats);
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
