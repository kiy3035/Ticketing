package com.inyoung.ticketing.hold.event;

import java.time.Duration;
import java.time.Instant;
import com.inyoung.ticketing.common.idempotency.IdempotencyService;
import com.inyoung.ticketing.notification.dto.NotificationItemResponse;
import com.inyoung.ticketing.notification.service.NotificationService;
import com.inyoung.ticketing.notification.service.SseNotificationService;
import com.inyoung.ticketing.seat.domain.Seat;
import com.inyoung.ticketing.seat.repository.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 홀드 만료(HOLD_EXPIRED)·예약 확정(RESERVATION_CONFIRMED) 이벤트를 수신해
 * Redis 알림 저장(폴링 백업) + SSE 실시간 전송을 수행하는 Kafka 컨슈머.
 *
 * <p><b>멱등성 보장</b>: Kafka at-least-once 특성으로 같은 메시지가 재전송될 수 있다.
 * holdToken + eventType 을 멱등성 키로 사용해 사용자에게 중복 알림이 전송되지 않도록 한다.
 * PaymentCompleteEventConsumer 와 동일한 패턴을 적용한다.
 *
 * <p>처리 실패 시 DLQ({@code ticketing.seat-hold-events.DLT})로 전송되며,
 * 운영 모니터링을 통해 수동 재처리할 수 있다.</p>
 */
@Component
public class SeatHoldEventConsumer {
	private static final Logger log = LoggerFactory.getLogger(SeatHoldEventConsumer.class);
	private static final String IDEMPOTENCY_KEY_PREFIX = "kafka:seat-hold-event:";
	private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

	private final NotificationService notificationService;
	private final SseNotificationService sseNotificationService;
	private final SeatRepository seatRepository;
	private final IdempotencyService idempotencyService;

	public SeatHoldEventConsumer(
		NotificationService notificationService,
		SseNotificationService sseNotificationService,
		SeatRepository seatRepository,
		IdempotencyService idempotencyService
	) {
		this.notificationService = notificationService;
		this.sseNotificationService = sseNotificationService;
		this.seatRepository = seatRepository;
		this.idempotencyService = idempotencyService;
	}

	@KafkaListener(
		topics = { "ticketing.seat-hold-events" },
		containerFactory = "seatHoldKafkaListenerFactory"
	)
	public void handleSeatHoldEvent(SeatHoldEvent event) {
		if (event == null) {
			return;
		}
		SeatHoldEventType type = event.getType();
		if (type != SeatHoldEventType.HOLD_EXPIRED
			&& type != SeatHoldEventType.RESERVATION_CONFIRMED) {
			return;
		}

		// holdToken + eventType 조합으로 멱등성 키 생성.
		// 같은 홀드에 EXPIRED·CONFIRMED 이벤트가 각각 올 수 있으므로 type 포함.
		String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + event.getHoldToken() + ":" + type.name();
		if (!idempotencyService.acquireKey(idempotencyKey, IDEMPOTENCY_TTL)) {
			log.info("홀드 이벤트 중복 수신 - 알림 발송 스킵: type={}, holdToken={}", type, event.getHoldToken());
			return;
		}

		try {
			log.info("홀드 이벤트 수신: type={}, userId={}, seatId={}", type, event.getUserId(), event.getSeatId());

			String message = buildMessage(event);
			NotificationItemResponse item = new NotificationItemResponse(
				type.name(),
				message,
				Instant.now()
			);
			notificationService.addNotification(event.getUserId(), item);
			sseNotificationService.sendNotification(event.getUserId(), item);

			idempotencyService.saveResult(idempotencyKey, "DONE", IDEMPOTENCY_TTL);
		} catch (RuntimeException e) {
			// 처리 실패 시 키 해제 → Kafka 재시도 가능
			idempotencyService.releaseKey(idempotencyKey);
			throw e;
		}
	}

	private String buildMessage(SeatHoldEvent event) {
		if (event.getType() == SeatHoldEventType.RESERVATION_CONFIRMED) {
			return seatRepository.findById(event.getSeatId())
				.map(this::formatConfirmedMessage)
				.orElse("결제가 완료되었습니다. 좌석 ID " + event.getSeatId());
		}
		return seatRepository.findById(event.getSeatId())
			.map(this::formatExpiredMessage)
			.orElse("예약이 만료되었습니다. 좌석 ID " + event.getSeatId());
	}

	private String formatExpiredMessage(Seat seat) {
		return "예약이 만료되었습니다. " + seat.getSection() + "구역 " + seat.getSeatNo();
	}

	private String formatConfirmedMessage(Seat seat) {
		return "결제가 완료되었습니다. " + seat.getSection() + "구역 " + seat.getSeatNo();
	}
}
