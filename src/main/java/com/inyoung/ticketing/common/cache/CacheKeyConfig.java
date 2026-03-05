package com.inyoung.ticketing.common.cache;

import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 공통 캐시 키 생성 규칙
@Configuration
public class CacheKeyConfig {
	@Bean("concertListKeyGenerator")
	public KeyGenerator concertListKeyGenerator() {
		return (target, method, params) -> {
			String query = params.length > 0 && params[0] != null ? String.valueOf(params[0]) : "";
			String category = params.length > 1 && params[1] != null ? String.valueOf(params[1]) : "";
			String past = params.length > 2 && params[2] != null ? String.valueOf(params[2]) : "false";
			return "q:" + query + ":c:" + category + ":p:" + past;
		};
	}
}
