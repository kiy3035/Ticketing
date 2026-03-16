package com.inyoung.ticketing.payment.event;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

// 결제 완료 이벤트를 Kafka 토픽(ticketing.payment-complete)에 발행한다.
// 결제 완료 후 이메일/SMS 알림은 이 이벤트를 통해 비동기로 처리된다.
@Service
public class PaymentCompleteEventPublisher {
	private static final String TOPIC = "ticketing.payment-complete";

	private final KafkaTemplate<String, PaymentCompleteEvent> kafkaTemplate;

	public PaymentCompleteEventPublisher(KafkaTemplate<String, PaymentCompleteEvent> kafkaTemplate) {
		this.kafkaTemplate = kafkaTemplate;
	}

	public void publishPaymentComplete(String paymentKey, String userId, Long concertId, Long amount) {
		PaymentCompleteEvent event = new PaymentCompleteEvent(paymentKey, userId, concertId, amount);
		kafkaTemplate.send(TOPIC, String.valueOf(concertId), event);
	}
}
