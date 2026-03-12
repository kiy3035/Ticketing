package com.inyoung.ticketing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 결제 관련 빈 설정.
 * RestTemplate 은 토스페이먼츠 API(결제 승인 등) 호출에 사용된다.
 */
@Configuration
public class PaymentConfig {

	@Bean
	public RestTemplate restTemplate() {
		return new RestTemplate();
	}
}
