package com.inyoung.ticketing.notification.service;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// SSE 연결 관리를 위한 서비스.
// 각 애플리케이션 인스턴스 내부 메모리에서만 연결을 관리하므로,
// 로드밸런서 레벨에서 Sticky Session(세션 고정)을 전제로 동작한다.
@Service
public class SseNotificationService {
	// 사용자 ID별 SSE 연결 저장 (동시성 안전).
	// 다중 스레드 환경에서도 안전하게 put/remove 할 수 있도록 ConcurrentHashMap 사용.
	private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

	// SSE 연결 생성 및 등록.
	// 동일 사용자의 기존 연결이 있으면 먼저 정리하고 새 emitter로 교체한다.
	public SseEmitter createConnection(String userId) {
		// 기존 연결이 있으면 제거
		removeConnection(userId);

		// 새 SSE 연결 생성 (타임아웃: 30분; 브라우저는 종료 시 재연결 시도).
		SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
		emitters.put(userId, emitter);

		// 연결 종료/타임아웃/에러 발생 시 메모리 누수를 막기 위해 Map 에서 emitter 제거.
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

	// 특정 사용자에게 알림 전송
	public void sendNotification(String userId, Object data) {
		SseEmitter emitter = emitters.get(userId);
		if (emitter != null) {
			try {
				emitter.send(SseEmitter.event()
					.name("notification")
					.data(data));
			} catch (IOException e) {
				// 전송 실패 시 연결 제거
				emitters.remove(userId);
				try {
					emitter.completeWithError(e);
				} catch (Exception ex) {
					// 무시
				}
			}
		}
	}

	// 연결 제거
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

	// 모든 연결 제거 (서버 종료 시 등)
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
