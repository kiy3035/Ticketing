package com.inyoung.ticketing.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// 애플리케이션 공통 설정 클래스
@Configuration
@EnableConfigurationProperties(TicketingProperties.class) // 커스텀 설정 바인딩 활성화
public class AppConfig {
}
