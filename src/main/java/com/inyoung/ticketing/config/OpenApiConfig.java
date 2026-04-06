package com.inyoung.ticketing.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * SpringDoc OpenAPI 설정.
 * /swagger-ui.html 에서 API 문서를 확인할 수 있다.
 */
@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI ticketingOpenAPI() {
		return new OpenAPI()
			.info(new Info()
				.title("콘서트 예매 시스템 API")
				.version("1.0.0")
				.description("""
					콘서트 예매 시스템의 REST API 명세.
					대기열 → 좌석 선택 → 홀드 → 결제 → 예약 확정 흐름을 제공한다.
					
					**인증**: JWT (Authorization: Bearer Access, X-Refresh-Token: Refresh). 로그인: POST /api/auth/login
					**결제**: 포인트 차감 / 토스페이먼츠 카드 결제(샌드박스)
					""")
				.contact(new Contact()
					.name("InYoung")
					.email("dev@concert-ticketing.com")))
			.servers(List.of(
				new Server().url("/").description("Current")
			));
	}
}
