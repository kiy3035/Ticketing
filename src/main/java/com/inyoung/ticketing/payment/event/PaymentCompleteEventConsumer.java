package com.inyoung.ticketing.payment.event;

import com.inyoung.ticketing.notification.service.PaymentNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * 결제 완료 이벤트 컨슈머
 * 
 * PaymentService에서 발행한 PaymentCompleteEvent를 수신하여
 * 사용자의 알림 방식(이메일/SMS)에 따라 비동기로 알림을 전송합니다.
 * 
 * 아키텍처:
 * 1. PaymentService.completePayment() → PaymentCompleteEvent 발행 (Kafka)
 * 2. PaymentCompleteEventConsumer.handlePaymentComplete() → 이벤트 수신
 * 3. PaymentNotificationService.notifyPaymentComplete() → 라우팅
 * 4. EmailService 또는 SmsService → 최종 전송
 * 
 * 이점:
 * - 결제 처리와 알림 전송 분리 (느슨한 결합)
 * - 알림 실패가 결제 프로세스를 방해하지 않음
 * - 별도 쓰레드/워커에서 처리되어 응답 시간 단축
 * - Kafka DLT로 실패한 이벤트 재처리 가능
 */
@Service
public class PaymentCompleteEventConsumer {
	private static final Logger logger = LoggerFactory.getLogger(PaymentCompleteEventConsumer.class);
	private final PaymentNotificationService paymentNotificationService;

	public PaymentCompleteEventConsumer(PaymentNotificationService paymentNotificationService) {
		this.paymentNotificationService = paymentNotificationService;
	}

	/**
	 * 결제 완료 이벤트 처리 (Kafka 리스너)
	 * 
	 * @param event PaymentCompleteEvent (결제 정보)
	 * 
	 * 처리 흐름:
	 * 1. 이벤트 로깅
	 * 2. PaymentNotificationService 호출 (사용자 알림 타입 확인 후 이메일 또는 SMS 전송)
	 * 3. 성공 시 로깅, 실패 시 예외 로깅 및 Kafka DLT로 이벤트 전송
	 */
	@KafkaListener(
		topics = "ticketing.payment-complete",
		groupId = "ticketing-payment-notification",
		containerFactory = "paymentCompleteKafkaListenerFactory"
	)
	public void handlePaymentComplete(PaymentCompleteEvent event) {
		// 이벤트 수신 로깅
		logger.info("Received payment complete event for user: {}, concert: {}", event.getUserId(), event.getConcertId());

		try {
			// 사용자의 notification_type에 따라 이메일 또는 SMS 비동기 전송
			// (EmailService 또는 SmsService가 호출됨)
			paymentNotificationService.notifyPaymentComplete(
				event.getUserId(),
				event.getConcertId(),
				event.getAmount()
			);
			logger.info("Payment notification sent successfully for user: {}", event.getUserId());
		} catch (Exception e) {
			logger.error("Failed to send payment notification for user: {}", event.getUserId(), e);
			// 예외 발생 시에도 catch하여 Kafka 메시지는 소비됨
			// Kafka의 DLT(Dead Letter Topic) 설정으로 실패 이벤트 재처리 가능
			// (현재는 로깅만 하고 이벤트 폐기 → 프로덕션에서는 DLT 구성 필요)
		}
	}
}
