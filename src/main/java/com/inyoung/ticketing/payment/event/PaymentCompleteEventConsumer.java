package com.inyoung.ticketing.payment.event;

import com.inyoung.ticketing.notification.service.PaymentNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

// 결제 완료 이벤트 수신 → 사용자 알림 방식(이메일/SMS)에 따라 비동기 전송.
// 예외를 잡지 않아 KafkaConfig의 DefaultErrorHandler(3회 재시도 + DLT)가 정상 동작한다.
@Service
public class PaymentCompleteEventConsumer {
	private static final Logger logger = LoggerFactory.getLogger(PaymentCompleteEventConsumer.class);
	private final PaymentNotificationService paymentNotificationService;

	public PaymentCompleteEventConsumer(PaymentNotificationService paymentNotificationService) {
		this.paymentNotificationService = paymentNotificationService;
	}

	@KafkaListener(
		topics = "ticketing.payment-complete",
		groupId = "ticketing-payment-notification",
		containerFactory = "paymentCompleteKafkaListenerFactory"
	)
	public void handlePaymentComplete(PaymentCompleteEvent event) {
		logger.info("결제 완료 이벤트 수신: userId={}, concertId={}", event.getUserId(), event.getConcertId());
		paymentNotificationService.notifyPaymentComplete(
			event.getUserId(),
			event.getConcertId(),
			event.getAmount()
		);
		logger.info("결제 알림 전송 완료: userId={}", event.getUserId());
	}
}
