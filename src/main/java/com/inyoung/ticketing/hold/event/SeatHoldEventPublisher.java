package com.inyoung.ticketing.hold.event;

import java.time.Instant;
import com.inyoung.ticketing.config.TicketingProperties;
import com.inyoung.ticketing.hold.store.HoldInfo;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

// 홀드/예약 이벤트 발행
@Service
public class SeatHoldEventPublisher {
	private final KafkaTemplate<String, SeatHoldEvent> kafkaTemplate;
	private final TicketingProperties properties;

	public SeatHoldEventPublisher(
		KafkaTemplate<String, SeatHoldEvent> kafkaTemplate,
		TicketingProperties properties
	) {
		this.kafkaTemplate = kafkaTemplate;
		this.properties = properties;
	}

	public void publish(SeatHoldEventType type, HoldInfo info) {
		SeatHoldEvent event = new SeatHoldEvent(
			type,
			info.getHoldToken(),
			info.getConcertId(),
			info.getSeatId(),
			info.getUserId(),
			info.getExpiresAt(),
			Instant.now()
		);
		String topic = properties.getKafka().getHoldTopic();
		kafkaTemplate.send(topic, String.valueOf(info.getSeatId()), event);
	}
}
