package com.inyoung.ticketing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

// 애플리케이션 진입점: 컴포넌트 스캔과 캐시/스케줄링을 활성화한다.
@SpringBootApplication
@EnableCaching // Redis 캐시 활성화
@EnableScheduling // 홀드 정리 스케줄러 실행
public class TicketingApplication {

	public static void main(String[] args) {
		SpringApplication.run(TicketingApplication.class, args);
	}

}
