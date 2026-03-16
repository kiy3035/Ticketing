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

// Redis 캐시 매니저 설정.
// Instant 등 Java time 타입을 JSON 직렬화하기 위해 JavaTimeModule 을 등록하고,
// 기본 TTL 5분을 적용해 콘서트 목록 등 캐시 데이터가 자동 만료되도록 한다.
@Configuration
public class RedisConfig {
	@Bean
	public RedisCacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
		ObjectMapper objectMapper = new ObjectMapper()
			.registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

		RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
			.serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
				new GenericJackson2JsonRedisSerializer(objectMapper)
			))
			.entryTtl(Duration.ofMinutes(5));

		return RedisCacheManager.builder(connectionFactory)
			.cacheDefaults(config)
			.build();
	}
}
