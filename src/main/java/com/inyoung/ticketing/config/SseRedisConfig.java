package com.inyoung.ticketing.config;

import com.inyoung.ticketing.notification.service.SseNotificationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * SSE 알림용 Redis Pub/Sub 구독 설정. [신규 파일]
 *
 * ── 왜 이 파일이 필요한가 ────────────────────────────────────────────────
 * SseNotificationService.sendNotification()이 이제 Redis PUBLISH를 사용하므로,
 * 각 인스턴스가 해당 채널을 SUBSCRIBE하고 있어야 메시지를 수신할 수 있다.
 * Spring Data Redis의 RedisMessageListenerContainer가 그 역할을 담당한다.
 *
 * ── 기존 RedisConfig에 추가하지 않은 이유 ────────────────────────────────
 * RedisConfig → RedisCacheManager(캐시 설정 전담) 이고,
 * 이 파일은 Pub/Sub 리스너 전담이라 관심사를 분리했다.
 * (RedisConfig에서 SseNotificationService를 참조하면 config ↔ notification 패키지 간
 * 의존 방향이 뒤섞이는 문제도 있음)
 *
 * ── 동작 원리 ─────────────────────────────────────────────────────────
 * 애플리케이션 시작 시 RedisMessageListenerContainer가 Redis에 PSUBSCRIBE 명령을 실행.
 * 이후 누군가 "sse:notify:{userId}" 채널에 PUBLISH하면 Redis가 모든 구독 인스턴스에
 * 메시지를 전달하고, 각 인스턴스의 SseNotificationService.onMessage()가 호출된다.
 */
@Configuration
public class SseRedisConfig {

	/**
	 * Redis Pub/Sub 구독 컨테이너 빈.
	 *
	 * @param connectionFactory  Spring Boot 자동 설정으로 주입되는 Redis 연결 팩토리.
	 *                           Pub/Sub 전용 커넥션은 일반 커맨드 커넥션과 별도로 관리된다.
	 * @param sseNotificationService  MessageListener를 구현한 서비스.
	 *                                onMessage() 콜백을 통해 수신 메시지를 처리한다.
	 */
	@Bean
	public RedisMessageListenerContainer sseRedisMessageListenerContainer(
		RedisConnectionFactory connectionFactory,
		SseNotificationService sseNotificationService
	) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);

		// PatternTopic("sse:notify:*") → Redis PSUBSCRIBE 명령으로 와일드카드 패턴 구독.
		// 인스턴스가 2대면 양쪽 모두 이 구독을 가지며, PUBLISH된 메시지는 양쪽 모두에 전달된다.
		// 실제 emitter.send()는 SseNotificationService.onMessage() 안에서
		// 에미터가 있는 인스턴스만 수행하므로 중복 전송 걱정 없음.
		container.addMessageListener(
			sseNotificationService,
			new PatternTopic(SseNotificationService.CHANNEL_PREFIX + "*")
		);

		return container;
	}
}
