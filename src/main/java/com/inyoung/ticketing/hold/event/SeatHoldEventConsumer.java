package com.inyoung.ticketing.hold.event;

import java.time.Instant;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inyoung.ticketing.notification.dto.NotificationItemResponse;
import com.inyoung.ticketing.notification.service.NotificationService;
import com.inyoung.ticketing.notification.service.SseNotificationService;
import com.inyoung.ticketing.seat.domain.Seat;
import com.inyoung.ticketing.seat.repository.SeatRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// 홀드 만료(HOLD_EXPIRED)·예약 확정(RESERVATION_CONFIRMED) 이벤트를 수신해
// Redis 알림 저장(폴링 백업) + SSE 실시간 전송을 수행하는 Kafka 소비자.
@Component
public class SeatHoldEventConsumer {
	private final NotificationService notificationService;
	private final SseNotificationService sseNotificationService;
	private final SeatRepository seatRepository;
	private final ObjectMapper objectMapper;

	public SeatHoldEventConsumer(
		NotificationService notificationService,
		SseNotificationService sseNotificationService,
		SeatRepository seatRepository,
		ObjectMapper objectMapper
	) {
		this.notificationService = notificationService;
		this.sseNotificationService = sseNotificationService;
		this.seatRepository = seatRepository;
		this.objectMapper = objectMapper;
	}

	@KafkaListener(
		topics = { "ticketing.seat-hold-events" },
		containerFactory = "seatHoldKafkaListenerFactory"
	)
	public void handleSeatHoldEvent(String payload) {
		SeatHoldEvent event = parseEvent(payload);
		if (event == null) {
			return;
		}
		SeatHoldEventType type = event.getType();
		if (type != SeatHoldEventType.HOLD_EXPIRED
			&& type != SeatHoldEventType.RESERVATION_CONFIRMED) {
			return;
		}
		String message = buildMessage(event);
		NotificationItemResponse item = new NotificationItemResponse(
			type.name(),
			message,
			Instant.now()
		);
		
		// Redis에 알림 저장 (폴링용 백업)
		notificationService.addNotification(event.getUserId(), item);
		
		// SSE로 실시간 알림 전송
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

	private SeatHoldEvent parseEvent(String payload) {
		if (payload == null || payload.isBlank()) {
			return null;
		}
		try {
			return objectMapper.readValue(payload, SeatHoldEvent.class);
		} catch (JsonProcessingException e) {
			return null;
		}
	}
}
