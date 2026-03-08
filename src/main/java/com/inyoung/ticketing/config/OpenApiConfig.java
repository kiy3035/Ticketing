package com.inyoung.ticketing.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger(OpenAPI 3) 명세 설정
 * 
 * 브라우저에서 /swagger-ui.html 로 API 명세를 한눈에 확인할 수 있습니다.
 */
@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI openAPI() {
		return new OpenAPI()
			.info(new Info()
				.title("콘서트 예매 API")
				.description("콘서트 예매 시스템 REST API 명세. 인증·대기열·홀드·예약·결제·알림·지표·관리자 API 포함.")
				.version("1.0"));
	}
}
