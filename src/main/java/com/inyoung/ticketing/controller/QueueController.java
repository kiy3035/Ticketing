package com.inyoung.ticketing.controller;

import com.inyoung.ticketing.dto.QueueStatusResponse;
import com.inyoung.ticketing.dto.QueueTicketResponse;
import com.inyoung.ticketing.queue.QueueService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.validation.annotation.Validated;

// 대기열 API 컨트롤러(확장 포인트)
@RestController
@Validated
@RequestMapping("/api/queue")
public class QueueController {
	private final QueueService queueService;

	// 서비스 주입
	public QueueController(QueueService queueService) {
		this.queueService = queueService;
	}

	// 대기열 토큰 발급
	@GetMapping("/ticket")
	@ResponseStatus(HttpStatus.CREATED)
	public QueueTicketResponse issueTicket(@RequestParam @NotBlank String userId) {
		String token = queueService.issueToken(userId);
		return new QueueTicketResponse(token, System.currentTimeMillis());
	}

	// 대기 순번 조회
	@GetMapping("/status")
	public QueueStatusResponse status(@RequestParam @NotBlank String token) {
		Long rank = queueService.getRank(token);
		if (rank == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Token not found");
		}
		return new QueueStatusResponse(token, rank);
	}
}
