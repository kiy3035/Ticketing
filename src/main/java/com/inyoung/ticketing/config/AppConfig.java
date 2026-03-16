package com.inyoung.ticketing.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TicketingProperties.class)
public class AppConfig {
}
