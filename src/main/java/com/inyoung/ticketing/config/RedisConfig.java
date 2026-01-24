package com.inyoung.ticketing.config;

import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

// Redis 캐시 매니저 설정
@Configuration
public class RedisConfig {
	@Bean
	public RedisCacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
		// Java time 타입(Instant 등) 직렬화를 위한 ObjectMapper 설정
		ObjectMapper objectMapper = new ObjectMapper()
			.registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

		// 캐시 값 직렬화 방식과 기본 TTL을 설정한다.
		RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
			.serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
				new GenericJackson2JsonRedisSerializer(objectMapper)
			))
			.entryTtl(Duration.ofMinutes(5));

		// 기본 캐시 설정을 적용한 Redis 캐시 매니저 생성
		return RedisCacheManager.builder(connectionFactory)
			.cacheDefaults(config)
			.build();
	}
}
