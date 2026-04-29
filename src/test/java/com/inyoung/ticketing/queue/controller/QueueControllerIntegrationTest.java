package com.inyoung.ticketing.queue.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.metrics.service.ActiveUserTracker;
import com.inyoung.ticketing.queue.service.QueueService;
import com.inyoung.ticketing.seat.service.SeatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 대기열 API 통합 테스트.
 * POST /api/queue/enter 에 대한 HTTP 계약을 검증한다.
 * QueueController만 로딩하고, QueueService·SeatService 등은 Mock으로 대체하여
 * 실제 Redis/DB 없이 "요청 → 컨트롤러 → 응답 형식"이 기대대로 동작하는지 확인한다.
 */
@WebMvcTest(QueueController.class)
@AutoConfigureMockMvc(addFilters = false)
class QueueControllerIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ActiveUserTracker activeUserTracker;
	@MockitoBean
	private QueueService queueService;
	@MockitoBean
	private SeatService seatService;
	@MockitoBean
	private TicketingProperties properties;

	@BeforeEach
	void setUp() {
		when(properties.getQueue()).thenReturn(new TicketingProperties.Queue());
	}

	/**
	 * POST /api/queue/enter?concertId=1 호출 시
	 * - HTTP 201 Created 인지
	 * - 응답 body(ApiResponse 래핑)에 data.token, data.rank, data.totalWaiting 이
	 *   서비스가 반환한 값(token-123, 1, 1)과 일치하는지 검증.
	 * 대기열 진입 API의 성공 경로와 JSON 형식이 기대대로인지 확인한다.
	 */
	@Test
	void enter_returns201WithTokenAndRank_whenQueueEnterSucceeds() throws Exception {
		var tokenInfo = new QueueService.QueueTokenInfo("token-123", 1L, 1L);
		when(queueService.enterQueue(anyLong(), anyString())).thenReturn(tokenInfo);

		mockMvc.perform(post("/api/queue/enter").param("concertId", "1"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.token").value("token-123"))
			.andExpect(jsonPath("$.data.rank").value(1))
			.andExpect(jsonPath("$.data.totalWaiting").value(1));
	}
}
