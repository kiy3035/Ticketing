package com.inyoung.ticketing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 스프링 부트 애플리케이션 컨텍스트 로딩 테스트.
 * 모든 Bean(Redis, Kafka, Security, Controller, Service 등)이 의존성 없이 올바르게 등록되는지
 * 한 번에 검증한다. contextLoads가 성공하면 기본 설정·조합에 문제가 없다는 의미.
 */
@SpringBootTest
class TicketingApplicationTests {

	/**
	 * Spring Boot 애플리케이션 컨텍스트가 예외 없이 로드되는지 확인한다.
	 * 실패 시 설정 오류, 누락된 Bean, 순환 참조 등이 원인일 수 있다.
	 */
	@Test
	void contextLoads() {
	}

}
