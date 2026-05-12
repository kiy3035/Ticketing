package com.inyoung.ticketing.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inyoung.ticketing.notification.dto.NotificationItemResponse;
import com.inyoung.ticketing.notification.service.SseNotificationService;
import com.inyoung.ticketing.support.IntegrationTestBase;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 다중 인스턴스 브로드캐스트 통합 테스트.
 *
 * <p><b>검증 대상</b>: nginx 뒤 앱 서버가 2대인 환경에서 발생하는 다음 시나리오를 코드로 증명한다.</p>
 *
 * <pre>
 *   사용자 X 가 app1 에 SSE 연결
 *   ↓
 *   결제 완료 Kafka 이벤트가 app2 에서 컨슈밍됨
 *   ↓
 *   app2 가 sendNotification(X, ...) 호출 → Redis PUBLISH
 *   ↓
 *   app1 의 RedisMessageListenerContainer 가 메시지 수신 → onMessage()
 *   ↓
 *   app1 의 emitters 맵에 X 가 있음 → emitter.send() 로 브라우저에 전달
 * </pre>
 *
 * <p><b>구현 방식</b>: 같은 JVM 안에서 두 개의 {@link SseNotificationService} 인스턴스를 만들어
 * "두 대의 서로 다른 앱 인스턴스"를 시뮬레이션한다. 각 인스턴스는 자기만의 emitters 맵을 가지고
 * 별도 {@link RedisMessageListenerContainer} 로 같은 채널을 구독한다.</p>
 *
 * <p>실제 두 JVM 을 띄우지 않는 이유: SSE 의 본질적 검증 포인트는
 * "에미터를 보유하지 않은 인스턴스에서 publish 가 시작돼도 보유 인스턴스가 받아 전달하는가" 이고,
 * 이는 같은 Redis 를 공유하는 두 개의 listener container 만으로 동등하게 증명된다.</p>
 */
class SseNotificationMultiInstanceIntegrationTest extends IntegrationTestBase {

	@Autowired private SseNotificationService instanceA;
	@Autowired private StringRedisTemplate redisTemplate;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private RedisConnectionFactory connectionFactory;

	// "또 하나의 앱 서버" 역할. 수동 생성하여 별도 emitters 맵을 갖는다.
	private SseNotificationService instanceB;
	// instanceB 전용 Pub/Sub 구독 컨테이너. 운영 환경의 두 번째 앱 서버에서 자동 등록되는 빈을 흉내낸다.
	private RedisMessageListenerContainer containerB;

	@BeforeEach
	void setUp() {
		instanceA.removeAllConnections();

		// instanceB 와 그 전용 listener container 를 띄워 "2대 운영" 환경을 시뮬레이션
		instanceB = new SseNotificationService(redisTemplate, objectMapper);
		containerB = new RedisMessageListenerContainer();
		containerB.setConnectionFactory(connectionFactory);
		containerB.afterPropertiesSet();
		containerB.start();
		containerB.addMessageListener(
			instanceB,
			new PatternTopic(SseNotificationService.CHANNEL_PREFIX + "*")
		);
	}

	@AfterEach
	void tearDown() throws Exception {
		if (containerB != null) {
			containerB.stop();
			containerB.destroy();
		}
		if (instanceB != null) {
			instanceB.removeAllConnections();
		}
		instanceA.removeAllConnections();
	}

	// ─────────────────────────────────────────────────────────────────────────────
	// 시나리오 1: 핵심 — "다른 인스턴스에서 발행한 메시지를 자기 인스턴스가 받는다"
	//   운영 케이스: 사용자가 app1 에 연결, Kafka 컨슈머는 app2 에서 실행되어 알림 발행.
	// ─────────────────────────────────────────────────────────────────────────────
	@Test
	@DisplayName("B(다른 인스턴스)에서 발행한 알림을 A(연결 보유 인스턴스)가 수신해 emitter 에 전달한다")
	void crossInstanceBroadcast_publisherIsB_subscriberIsA() throws Exception {
		// given: 사용자 X 가 instanceA 에 SSE 연결 (실제 emitter 대신 spy 로 send 호출 캡처)
		String userId = "user-" + UUID.randomUUID();
		SseEmitter spyEmitterOnA = spy(new SseEmitter(60_000L));
		injectEmitter(instanceA, userId, spyEmitterOnA);

		// when: instanceB 에서 같은 사용자에게 알림 발행 (= 다른 앱 서버가 Kafka 이벤트 처리)
		NotificationItemResponse item = new NotificationItemResponse(
			"PAYMENT_COMPLETED", "결제가 완료되었습니다.", Instant.now()
		);
		instanceB.sendNotification(userId, item);

		// then: Redis Pub/Sub 비동기라 Awaitility 로 대기, A 의 emitter 에 send 호출됨
		Awaitility.await()
			.atMost(Duration.ofSeconds(3))
			.untilAsserted(() ->
				verify(spyEmitterOnA, atLeastOnce())
					.send(any(SseEmitter.SseEventBuilder.class))
			);
	}

	// ─────────────────────────────────────────────────────────────────────────────
	// 시나리오 2: broadcast 본성 — 같은 사용자가 양쪽에 연결돼 있으면 양쪽 모두 수신
	//   운영에서 흔한 케이스는 아니지만, "PUBLISH 가 모든 구독자에게 전달된다" 는 본성 검증.
	// ─────────────────────────────────────────────────────────────────────────────
	@Test
	@DisplayName("같은 userId 가 A·B 양쪽에 연결돼 있으면 두 emitter 모두 메시지를 수신한다")
	void broadcast_bothInstancesReceive_whenUserConnectedToBoth() throws Exception {
		// given: 같은 userId 가 두 인스턴스에 동시 연결돼 있는 상태
		String userId = "user-" + UUID.randomUUID();
		SseEmitter spyEmitterOnA = spy(new SseEmitter(60_000L));
		SseEmitter spyEmitterOnB = spy(new SseEmitter(60_000L));
		injectEmitter(instanceA, userId, spyEmitterOnA);
		injectEmitter(instanceB, userId, spyEmitterOnB);

		// when: A 에서 publish (B 에서 publish 해도 결과 동일)
		instanceA.sendNotification(userId,
			new NotificationItemResponse("HOLD_EXPIRED", "홀드가 만료되었습니다.", Instant.now()));

		// then: 양쪽 emitter 모두 send 호출 — broadcast 동작 증명
		Awaitility.await()
			.atMost(Duration.ofSeconds(3))
			.untilAsserted(() -> {
				verify(spyEmitterOnA, atLeastOnce())
					.send(any(SseEmitter.SseEventBuilder.class));
				verify(spyEmitterOnB, atLeastOnce())
					.send(any(SseEmitter.SseEventBuilder.class));
			});
	}

	// ─────────────────────────────────────────────────────────────────────────────
	// 시나리오 3: 격리 — 다른 사용자에게 보낸 메시지가 무관한 사용자 emitter 로 새지 않는다
	//   채널이 userId 별로 분리(`sse:notify:{userId}`)되어 있는지 검증.
	// ─────────────────────────────────────────────────────────────────────────────
	@Test
	@DisplayName("user2 에게 보낸 알림이 user1 emitter 로 전달되지 않는다 (채널 격리)")
	void userIsolation_publishToOtherUser_doesNotReachThisUserEmitter() throws Exception {
		// given: A 에 user1 만 연결 (user2 는 어디에도 연결 안 됨)
		String user1 = "user1-" + UUID.randomUUID();
		String user2 = "user2-" + UUID.randomUUID();
		SseEmitter spyEmitterUser1OnA = spy(new SseEmitter(60_000L));
		injectEmitter(instanceA, user1, spyEmitterUser1OnA);

		// when: B 에서 user2 에게 publish
		instanceB.sendNotification(user2,
			new NotificationItemResponse("HOLD_CREATED", "홀드 생성", Instant.now()));

		// then: 1초 동안 user1 emitter 에는 send 가 한 번도 호출되지 않아야 함
		// during(1s) — "동안 내내 조건이 유지되어야 한다" 검증
		Awaitility.await()
			.during(Duration.ofSeconds(1))
			.atMost(Duration.ofSeconds(2))
			.untilAsserted(() ->
				verify(spyEmitterUser1OnA, never())
					.send(any(SseEmitter.SseEventBuilder.class))
			);
	}

	// ─────────────────────────────────────────────────────────────────────────────
	// 시나리오 4: no-op — 어디에도 emitter 가 없으면 publish 는 조용히 흡수된다
	//   사용자가 SSE 연결 끊은 직후 발생한 알림이 예외를 일으키지 않는지 검증.
	// ─────────────────────────────────────────────────────────────────────────────
	@Test
	@DisplayName("어느 인스턴스에도 emitter 가 없는 사용자에게 publish 해도 예외 없이 통과한다")
	void noEmitterAnywhere_publishCompletesQuietly() {
		String ghostUser = "ghost-" + UUID.randomUUID();

		// when & then: 예외 없이 리턴되면 PASS
		instanceA.sendNotification(ghostUser,
			new NotificationItemResponse("HOLD_CREATED", "홀드 생성", Instant.now()));
		instanceB.sendNotification(ghostUser,
			new NotificationItemResponse("HOLD_CREATED", "홀드 생성", Instant.now()));
	}

	// ─────────────────────────────────────────────────────────────────────────────
	// 헬퍼
	// ─────────────────────────────────────────────────────────────────────────────

	/**
	 * SseNotificationService 의 private emitters 맵에 직접 emitter 를 주입한다.
	 * createConnection() 으로 만들면 실제 SseEmitter 가 생성돼 spy/verify 가 어렵기 때문에,
	 * 테스트에서는 mockito spy 로 감싼 emitter 를 reflection 으로 직접 등록한다.
	 */
	@SuppressWarnings("unchecked")
	private void injectEmitter(SseNotificationService instance, String userId, SseEmitter emitter) throws Exception {
		Field field = SseNotificationService.class.getDeclaredField("emitters");
		field.setAccessible(true);
		Map<String, SseEmitter> map = (Map<String, SseEmitter>) field.get(instance);
		map.put(userId, emitter);
	}
}
