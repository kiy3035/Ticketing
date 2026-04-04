package com.inyoung.ticketing.hold.event;

import java.time.Instant;
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
 * <p>KafkaConfig에서 {@code SeatHoldEvent} 타입의 JsonDeserializer를 설정해
 * Producer와 동일한 역직렬화 체인을 사용한다. (기존 String 수동 파싱에서 통일)</p>
 *
 * <p>처리 실패 시 DLQ({@code ticketing.seat-hold-events.DLT})로 전송되며,
 * 운영 모니터링을 통해 수동 재처리할 수 있다.</p>
 */
@Component
public class SeatHoldEventConsumer {
	private static final Logger log = LoggerFactory.getLogger(SeatHoldEventConsumer.class);

	private final NotificationService notificationService;
	private final SseNotificationService sseNotificationService;
	private final SeatRepository seatRepository;

	public SeatHoldEventConsumer(
		NotificationService notificationService,
		SseNotificationService sseNotificationService,
		SeatRepository seatRepository
	) {
		this.notificationService = notificationService;
		this.sseNotificationService = sseNotificationService;
		this.seatRepository = seatRepository;
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

		log.info("홀드 이벤트 수신: type={}, userId={}, seatId={}", type, event.getUserId(), event.getSeatId());

		String message = buildMessage(event);
		NotificationItemResponse item = new NotificationItemResponse(
			type.name(),
			message,
			Instant.now()
		);
		notificationService.addNotification(event.getUserId(), item);
		sseNotificationService.sendNotification(event.getUserId(), item);
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
