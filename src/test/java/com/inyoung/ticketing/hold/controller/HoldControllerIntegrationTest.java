package com.inyoung.ticketing.hold.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inyoung.ticketing.auth.jwt.JwtAuthenticationService;
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
import org.springframework.test.web.servlet.MockMvc;

/**
 * 홀드 API 슬라이스 테스트.
 * POST /api/holds 의 HTTP 계약(상태 코드 201, 응답 body)을 검증한다.
 *
 * @WebMvcTest 에서 SecurityConfig 가 의존하는 UsersService(UserDetailsService)가 스캔 범위 밖이라
 * 기본 Spring Security(CSRF 활성화, 폼 로그인)가 적용된다.
 * user() PostProcessor 로 SecurityContext 를 세션에 주입하고,
 * csrf() 로 CSRF 토큰을 함께 보내면 기본 Security 를 정상 통과한다.
 */
@WebMvcTest(HoldController.class)
class HoldControllerIntegrationTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private JwtAuthenticationService jwtAuthenticationService;
	@MockitoBean
	private ActiveUserTracker activeUserTracker;
	@MockitoBean
	private HoldService holdService;

	/**
	 * POST /api/holds 호출 시
	 * - 인증된 사용자(user1)로 요청하고 HoldService 가 성공 응답을 반환할 때
	 * - HTTP 201 Created 인지
	 * - 응답 body(ApiResponse 래핑)에 holdToken 필드가 포함되는지 검증.
	 */
	@Test
	void createHold_returns201WithHoldToken_whenSuccess() throws Exception {
		HoldRequest request = new HoldRequest(1L, 10L);
		HoldResponse response = new HoldResponse("hold-token-123", Instant.now().plusSeconds(600));
		when(holdService.createHold(any(HoldRequest.class), any(String.class))).thenReturn(response);

		mockMvc.perform(post("/api/holds")
				.with(user("user1").roles("USER"))
				.with(csrf())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.holdToken").value("hold-token-123"));
	}
}
