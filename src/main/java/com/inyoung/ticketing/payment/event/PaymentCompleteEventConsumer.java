package com.inyoung.ticketing.payment.event;

import java.time.Duration;
import com.inyoung.ticketing.common.idempotency.IdempotencyService;
import com.inyoung.ticketing.notification.service.PaymentNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * 결제 완료 이벤트 수신 → 사용자 알림(이메일/SMS) 비동기 발송.
 *
 * <p><b>멱등성 보장</b>: Kafka 는 at-least-once 전달 보장이라 같은 메시지가
 * 재시도·리밸런스 등으로 중복 수신될 수 있다. paymentKey 를 멱등성 키로 사용해
 * Redis 에 처리 마커를 넣고, 이미 처리된 paymentKey 는 알림 재발송을 스킵한다.
 *
 * <p>예외를 잡지 않아 KafkaConfig 의 DefaultErrorHandler(3회 재시도 + DLT)가 정상 동작한다.
 */
@Service
public class PaymentCompleteEventConsumer {
	private static final Logger logger = LoggerFactory.getLogger(PaymentCompleteEventConsumer.class);
	private static final String IDEMPOTENCY_KEY_PREFIX = "kafka:payment-complete:";
	// 알림 중복 차단 윈도우. 정상 운영 시 같은 paymentKey 재처리는 분 단위 내에서 발생.
	private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

	private final PaymentNotificationService paymentNotificationService;
	private final IdempotencyService idempotencyService;

	public PaymentCompleteEventConsumer(
		PaymentNotificationService paymentNotificationService,
		IdempotencyService idempotencyService
	) {
		this.paymentNotificationService = paymentNotificationService;
		this.idempotencyService = idempotencyService;
	}

	@KafkaListener(
		topics = "ticketing.payment-complete",
		groupId = "ticketing-payment-notification",
		containerFactory = "paymentCompleteKafkaListenerFactory"
	)
	public void handlePaymentComplete(PaymentCompleteEvent event) {
		String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + event.getPaymentKey();

		// 같은 paymentKey 의 알림이 이미 처리(또는 처리 중)면 스킵.
		// acquireKey 는 SET NX 기반이라 동시 중복 처리도 한 번만 통과한다.
		if (!idempotencyService.acquireKey(idempotencyKey, IDEMPOTENCY_TTL)) {
			logger.info("결제 완료 이벤트 중복 수신 - 알림 발송 스킵: paymentKey={}", event.getPaymentKey());
			return;
		}

		try {
			logger.info("결제 완료 이벤트 수신: userId={}, concertId={}", event.getUserId(), event.getConcertId());
			paymentNotificationService.notifyPaymentComplete(
				event.getUserId(),
				event.getConcertId(),
				event.getAmount()
			);
			// 처리 완료 마커. 이후 같은 paymentKey 가 와도 위 acquireKey 에서 false 반환.
			idempotencyService.saveResult(idempotencyKey, "DONE", IDEMPOTENCY_TTL);
			logger.info("결제 알림 전송 완료: userId={}", event.getUserId());
		} catch (RuntimeException e) {
			// 처리 실패 시 키를 풀어 Kafka 재시도 시 다시 발송할 수 있게 한다.
			idempotencyService.releaseKey(idempotencyKey);
			throw e;
		}
	}
}
