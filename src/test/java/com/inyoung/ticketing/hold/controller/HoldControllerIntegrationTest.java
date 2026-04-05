package com.inyoung.ticketing.hold.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inyoung.ticketing.hold.dto.HoldRequest;
import com.inyoung.ticketing.hold.dto.HoldResponse;
import com.inyoung.ticketing.hold.service.HoldService;
import com.inyoung.ticketing.metrics.service.ActiveUserTracker;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 홀드 API 통합 테스트.
 * POST /api/holds (좌석 홀드 생성)에 대한 HTTP 계약을 검증한다.
 * HoldController만 로딩하고 HoldService는 Mock으로 대체하여,
 * 인증된 사용자가 JSON body로 요청 시 200 OK와 응답 body에 holdToken이 포함되는지 확인한다.
 */
@WebMvcTest(HoldController.class)
class HoldControllerIntegrationTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private ActiveUserTracker activeUserTracker;
	@MockitoBean
	private HoldService holdService;

	/**
	 * POST /api/holds (Content-Type: application/json, body: concertId, seatId) 호출 시
	 * - 인증된 사용자(user1)로 요청하고, HoldService가 성공 응답을 반환하도록 Mock 했을 때
	 * - HTTP 200 OK 인지
	 * - 응답 body에 holdToken(hold-token-123)이 포함되는지(래핑 여부와 무관하게)
	 * 검증. 홀드 생성 API의 성공 경로와 클라이언트가 결제 페이지로 넘길 수 있는 토큰이 오는지 확인한다.
	 */
	@Test
	void createHold_returns200WithHoldToken_whenSuccess() throws Exception {
		var auth = new UsernamePasswordAuthenticationToken("user1", null, AuthorityUtils.createAuthorityList("ROLE_USER"));
		HoldRequest request = new HoldRequest(1L, 10L);
		HoldResponse response = new HoldResponse("hold-token-123", Instant.now().plusSeconds(600));
		when(holdService.createHold(any(HoldRequest.class), eq("user1"))).thenReturn(response);

		mockMvc.perform(post("/api/holds")
				.with(authentication(auth))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isOk())
			.andExpect(result -> {
				String body = result.getResponse().getContentAsString();
				if (body.contains("hold-token-123")) {
					return;
				}
				throw new AssertionError("Expected body to contain holdToken: " + body);
			});
	}
}
