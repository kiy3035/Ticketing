package com.inyoung.ticketing.event;

import java.time.Instant;
import com.inyoung.ticketing.domain.Seat;
import com.inyoung.ticketing.dto.NotificationItem;
import com.inyoung.ticketing.repository.SeatRepository;
import com.inyoung.ticketing.service.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// 홀드 만료 알림을 처리하는 Kafka 소비자
@Component
public class SeatHoldEventConsumer {
	private final NotificationService notificationService;
	private final SeatRepository seatRepository;

	public SeatHoldEventConsumer(
		NotificationService notificationService,
		SeatRepository seatRepository
	) {
		this.notificationService = notificationService;
		this.seatRepository = seatRepository;
	}

	@KafkaListener(topics = { "ticketing.seat-hold-events" })
	public void handleSeatHoldEvent(SeatHoldEvent event) {
		if (event == null || event.getType() != SeatHoldEventType.HOLD_EXPIRED) {
			return;
		}
		String message = buildMessage(event);
		NotificationItem item = new NotificationItem(
			SeatHoldEventType.HOLD_EXPIRED.name(),
			message,
			Instant.now()
		);
		notificationService.addNotification(event.getUserId(), item);
	}

	private String buildMessage(SeatHoldEvent event) {
		return seatRepository.findById(event.getSeatId())
			.map(seat -> formatSeatMessage(seat))
			.orElse("예약이 만료되었습니다. 좌석 ID " + event.getSeatId());
	}

	private String formatSeatMessage(Seat seat) {
		return "예약이 만료되었습니다. " + seat.getSection() + "구역 " + seat.getSeatNo();
	}
}
