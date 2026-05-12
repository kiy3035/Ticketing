package com.inyoung.ticketing.notification.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inyoung.ticketing.notification.dto.NotificationItemResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 연결 관리 서비스.
 *
 * ── 기존 구조 (단일 인스턴스 전제) ──────────────────────────────────────────
 * sendNotification()이 emitters 맵에서 직접 에미터를 꺼내 전송했다.
 * 즉, "알림을 보내는 코드"와 "SSE 연결이 저장된 인스턴스"가 반드시 같아야 동작했다.
 *
 * ── 문제 (nginx + 2대 구성 시) ────────────────────────────────────────────
 * Kafka 컨슈머(SeatHoldEventConsumer)는 어느 인스턴스에서든 실행될 수 있다.
 * 예) 사용자가 Instance-1에 SSE 연결 → Kafka 이벤트를 Instance-2가 처리
 *     → Instance-2의 emitters 맵에는 해당 사용자 에미터 없음 → 알림 누락
 * nginx Sticky Session(`ip_hash` 등)을 켜도 Kafka 컨슈머 실행 인스턴스는 제어할 수 없어 해결 불가.
 *
 * ── 변경 내용 ─────────────────────────────────────────────────────────────
 * 1. implements MessageListener 추가
 *    - Spring Data Redis의 Pub/Sub 수신 콜백 인터페이스.
 *    - SseRedisConfig가 이 클래스를 sse:notify:* 패턴 구독자로 등록한다.
 *
 * 2. 생성자에 StringRedisTemplate, ObjectMapper 주입 추가
 *    - 기존: 주입 필드 없음 (순수 인메모리)
 *    - 변경: Redis 발행(convertAndSend)과 JSON 변환을 위해 추가.
 *
 * 3. CHANNEL_PREFIX 상수 추가 (public static)
 *    - Redis 채널 이름 규칙: "sse:notify:{userId}"
 *    - SseRedisConfig에서도 구독 패턴("sse:notify:*") 조립에 사용하므로 public.
 *
 * 4. sendNotification() 로직 변경
 *    - 기존: emitters.get(userId)로 에미터 직접 조회 후 emitter.send()
 *    - 변경: ObjectMapper로 JSON 직렬화 → redisTemplate.convertAndSend()로 Redis 채널에 발행.
 *      실제 emitter.send()는 onMessage() 콜백에서 수행한다.
 *      → 발행 인스턴스가 어디든 상관없이 에미터를 보유한 인스턴스가 수신해서 전달.
 *    - Redis 장애(DataAccessException)는 여기서 흡수한다.
 *      catch하지 않으면 Kafka 컨슈머까지 예외가 전파되어 SSE 알림 실패 때문에
 *      정상 이벤트가 DLT로 이동하는 과잉 반응이 발생한다.
 *
 * 5. onMessage() 메서드 추가 (MessageListener 구현)
 *    - Redis가 구독 채널에 메시지를 전달할 때 호출하는 콜백.
 *    - 채널명에서 userId 추출 → 이 인스턴스의 emitters 맵 확인 → 있으면 emitter.send().
 *    - 에미터가 없으면(다른 인스턴스에 연결된 사용자) 아무것도 하지 않고 리턴. 이는 정상 흐름.
 */
@Service
public class SseNotificationService implements MessageListener {

	private static final Logger log = LoggerFactory.getLogger(SseNotificationService.class);

	/**
	 * Redis Pub/Sub 채널 접두사.
	 * 발행 채널: "sse:notify:{userId}" (sendNotification에서 사용)
	 * 구독 패턴: "sse:notify:*"       (SseRedisConfig에서 사용 → public으로 공개)
	 */
	public static final String CHANNEL_PREFIX = "sse:notify:";

	// 이 인스턴스에 현재 연결된 사용자 ID → SseEmitter 맵.
	// 다른 인스턴스의 연결 정보는 여기에 없다 — 의도된 설계.
	private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

	// [신규 주입] Redis 발행(convertAndSend)에 사용
	private final StringRedisTemplate redisTemplate;
	// [신규 주입] sendNotification의 Object → JSON 직렬화 / onMessage의 JSON → DTO 역직렬화에 사용
	private final ObjectMapper objectMapper;

	// [변경] 기존 기본 생성자 → Redis/ObjectMapper 주입받는 생성자로 교체
	public SseNotificationService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
	}

	// createConnection()은 변경 없음.
	// SSE 연결은 여전히 이 인스턴스 메모리에만 보관한다.
	// 연결 자체를 Redis로 올릴 필요는 없고, 알림 전달 경로만 Redis를 경유하면 된다.
	public SseEmitter createConnection(String userId) {
		removeConnection(userId);

		SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
		emitters.put(userId, emitter);

		emitter.onCompletion(() -> emitters.remove(userId));
		emitter.onTimeout(() -> {
			emitters.remove(userId);
			try {
				emitter.complete();
			} catch (Exception e) {
				// 무시
			}
		});
		emitter.onError((ex) -> emitters.remove(userId));

		return emitter;
	}

	/**
	 * [변경] 알림 발행 방식: 직접 전송 → Redis Pub/Sub 경유 전송.
	 *
	 * 기존 코드:
	 *   SseEmitter emitter = emitters.get(userId);
	 *   if (emitter != null) { emitter.send(...); }
	 *   → 이 인스턴스에 에미터가 없으면 전송 자체를 포기했음.
	 *
	 * 변경 코드:
	 *   1) data를 JSON으로 직렬화
	 *   2) Redis 채널 "sse:notify:{userId}"에 발행
	 *   3) 해당 채널을 구독 중인 모든 인스턴스가 onMessage()를 통해 수신
	 *   4) 에미터를 실제로 보유한 인스턴스만 emitter.send()를 실행
	 *
	 * DataAccessException(Redis 장애) 처리:
	 *   catch하지 않으면 Kafka 컨슈머(SeatHoldEventConsumer)까지 전파되어
	 *   재시도 3회 후 DLT로 이동한다. SSE 알림은 best-effort이므로 여기서 흡수한다.
	 */
	public void sendNotification(String userId, Object data) {
		try {
			String json = objectMapper.writeValueAsString(data);
			// Redis PUBLISH 명령 실행. 구독 중인 인스턴스 수만큼 onMessage()가 호출된다.
			redisTemplate.convertAndSend(CHANNEL_PREFIX + userId, json);
		} catch (JsonProcessingException e) {
			log.warn("SSE 알림 직렬화 실패: userId={}", userId, e);
		} catch (DataAccessException e) {
			// Redis 장애 시 SSE 알림은 포기하고 로그만 남긴다.
			// 예외를 다시 던지면 Kafka 재시도 → DLT로 이어지므로 여기서 흡수.
			log.warn("SSE 알림 Redis 발행 실패 (Redis 장애): userId={}, reason={}", userId, e.getMessage());
		}
	}

	/**
	 * [신규] Redis Pub/Sub 메시지 수신 콜백.
	 * SseRedisConfig에서 "sse:notify:*" 패턴으로 등록되어 애플리케이션 시작 시 자동 구독된다.
	 *
	 * 동작 흐름:
	 *   Redis 채널 "sse:notify:{userId}"에 메시지 발행
	 *   → 모든 인스턴스의 이 메서드가 호출됨
	 *   → 각 인스턴스가 자신의 emitters 맵에 해당 userId가 있는지 확인
	 *   → 있으면 emitter.send()로 브라우저에 전달, 없으면 조용히 리턴
	 *
	 * @param message 채널명(getChannel())과 메시지 본문(getBody())을 포함
	 * @param pattern 매칭된 패턴 바이트 배열 (사용 안 함, null 가능)
	 */
	@Override
	public void onMessage(Message message, byte[] pattern) {
		// 채널명에서 userId 추출: "sse:notify:user123" → "user123"
		String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
		if (!channel.startsWith(CHANNEL_PREFIX)) {
			return;
		}
		String userId = channel.substring(CHANNEL_PREFIX.length());

		// 이 인스턴스에 해당 사용자의 SSE 연결이 없으면 처리 불필요 (다른 인스턴스가 담당)
		SseEmitter emitter = emitters.get(userId);
		if (emitter == null) {
			return;
		}

		try {
			// Redis에서 받은 JSON 바이트 → NotificationItemResponse로 역직렬화 후 SSE 전송
			NotificationItemResponse item = objectMapper.readValue(
				message.getBody(), NotificationItemResponse.class
			);
			emitter.send(SseEmitter.event().name("notification").data(item));
		} catch (IOException e) {
			log.warn("SSE 알림 전송 실패: userId={}", userId, e);
			emitters.remove(userId);
			try {
				emitter.completeWithError(e);
			} catch (Exception ex) {
				// 무시
			}
		}
	}

	// removeConnection(), removeAllConnections()는 변경 없음
	public void removeConnection(String userId) {
		SseEmitter emitter = emitters.remove(userId);
		if (emitter != null) {
			try {
				emitter.complete();
			} catch (Exception e) {
				// 무시
			}
		}
	}

	public void removeAllConnections() {
		emitters.values().forEach(emitter -> {
			try {
				emitter.complete();
			} catch (Exception e) {
				// 무시
			}
		});
		emitters.clear();
	}
}
