package com.inyoung.ticketing.queue.controller;

import java.util.Optional;
import com.inyoung.ticketing.queue.dto.QueueAllowedResponse;
import com.inyoung.ticketing.queue.dto.QueueEnterResponse;
import com.inyoung.ticketing.queue.dto.QueueStatusResponse;
import com.inyoung.ticketing.queue.dto.QueueTicketResponse;
import com.inyoung.ticketing.queue.service.QueueService;
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

	// 서비스 주입
	public QueueController(QueueService queueService) {
		this.queueService = queueService;
	}

	// 콘서트 대기열 진입 (토큰 발급)
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
		return new QueueEnterResponse(
			tokenInfo.getToken(),
			tokenInfo.getRank(),
			tokenInfo.getTotalWaiting()
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
		return new QueueStatusResponse(token, rank, totalWaiting, isAllowed);
	}

	// 입장 허용 여부 확인
	@GetMapping("/allowed")
	public QueueAllowedResponse allowed(@RequestParam @NotBlank String token) {
		Optional<Long> allowedConcertId = queueService.isAllowed(token);
		return new QueueAllowedResponse(allowedConcertId.isPresent(), allowedConcertId.orElse(null));
	}

	// 콘서트별 대기인원 수 조회
	@GetMapping("/count")
	public long count(@RequestParam @NotNull Long concertId) {
		return queueService.countWaiting(concertId);
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
