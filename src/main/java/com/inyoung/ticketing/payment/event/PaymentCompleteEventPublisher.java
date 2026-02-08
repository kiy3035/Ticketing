package com.inyoung.ticketing.payment.event;

import com.inyoung.ticketing.config.TicketingProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

// 결제 완료 이벤트 발행
@Service
public class PaymentCompleteEventPublisher {
	private final KafkaTemplate<String, PaymentCompleteEvent> kafkaTemplate;
	private final TicketingProperties properties;

	public PaymentCompleteEventPublisher(
		KafkaTemplate<String, PaymentCompleteEvent> kafkaTemplate,
		TicketingProperties properties
	) {
		this.kafkaTemplate = kafkaTemplate;
		this.properties = properties;
	}

	// 결제 완료 이벤트 발행
	public void publishPaymentComplete(String paymentKey, String userId, Long concertId, Long amount) {
		PaymentCompleteEvent event = new PaymentCompleteEvent(paymentKey, userId, concertId, amount);
		String topic = "ticketing.payment-complete";  // Kafka 토픽
		kafkaTemplate.send(topic, String.valueOf(concertId), event);
	}
}
