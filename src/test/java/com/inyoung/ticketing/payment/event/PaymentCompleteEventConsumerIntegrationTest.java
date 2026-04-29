package com.inyoung.ticketing.payment.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.inyoung.ticketing.common.idempotency.IdempotencyService;
import com.inyoung.ticketing.notification.service.PaymentNotificationService;
import com.inyoung.ticketing.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Kafka 컨슈머 멱등성 통합 테스트.
 *
 * <p><b>왜 필요한가</b>: Kafka 는 at-least-once 전달을 보장한다.
 * 컨슈머가 메시지 처리 후 offset commit 직전에 장애가 나면 같은 메시지가 재전송된다.
 * 결제 완료 알림(이메일·SMS)이 두 번 발송되면 사용자에게 큰 혼란을 준다.
 *
 * <p><b>검증 시나리오</b>:
 * <ol>
 *     <li>같은 paymentKey 의 이벤트가 여러 번 들어와도 알림은 정확히 한 번만 발송</li>
 *     <li>다른 paymentKey 는 각각 독립적으로 알림 발송</li>
 *     <li>처리 중 예외 발생 시 멱등성 키가 해제되어 Kafka 재시도가 가능해야 함</li>
 * </ol>
 *
 * <p>실제 Redis 컨테이너(Testcontainers)로 IdempotencyService 의 SET NX 동작을 검증한다.
 * Kafka 컨슈머 자체는 직접 호출(KafkaListener 메서드 호출)로 트리거해 테스트 결정성을 확보한다.
 */
class PaymentCompleteEventConsumerIntegrationTest extends IntegrationTestBase {

	@Autowired private PaymentCompleteEventConsumer consumer;
	@Autowired private IdempotencyService idempotencyService;

	// 실제 빈을 감싸는 spy: 호출 횟수만 검증하고 동작은 그대로 통과시킨다.
	@MockitoSpyBean private PaymentNotificationService paymentNotificationService;

	@BeforeEach
	void setUp() {
		// Redis 의 멱등성 키를 정리해 테스트 간 영향 차단
		idempotencyService.releaseKey("kafka:payment-complete:test-key-1");
		idempotencyService.releaseKey("kafka:payment-complete:test-key-2");
		idempotencyService.releaseKey("kafka:payment-complete:test-key-fail");
	}

	/**
	 * 시나리오 1: 같은 paymentKey 의 이벤트를 3번 수신
	 * - 알림 발송은 정확히 1번
	 * - 두 번째·세 번째 수신은 멱등성 체크에 의해 스킵
	 */
	@Test
	@DisplayName("같은 paymentKey 이벤트 3번 수신 → 알림은 정확히 1번만 발송")
	void duplicateEvent_notifyOnlyOnce() {
		PaymentCompleteEvent event = new PaymentCompleteEvent(
			"test-key-1", "user1", 1L, 30000L
		);

		consumer.handlePaymentComplete(event);
		consumer.handlePaymentComplete(event);
		consumer.handlePaymentComplete(event);

		verify(paymentNotificationService, times(1))
			.notifyPaymentComplete("user1", 1L, 30000L);
	}

	/**
	 * 시나리오 2: 서로 다른 paymentKey 이벤트는 각각 처리
	 * - 두 paymentKey 각각 1번씩 알림 발송 (총 2번)
	 */
	@Test
	@DisplayName("서로 다른 paymentKey 는 독립적으로 처리")
	void differentPaymentKeys_processedIndependently() {
		PaymentCompleteEvent event1 = new PaymentCompleteEvent("test-key-1", "user1", 1L, 30000L);
		PaymentCompleteEvent event2 = new PaymentCompleteEvent("test-key-2", "user2", 2L, 50000L);

		consumer.handlePaymentComplete(event1);
		consumer.handlePaymentComplete(event2);

		verify(paymentNotificationService, times(1)).notifyPaymentComplete("user1", 1L, 30000L);
		verify(paymentNotificationService, times(1)).notifyPaymentComplete("user2", 2L, 50000L);
	}

	/**
	 * 시나리오 3: 멱등성 마커가 처리 후 Redis 에 남아있어야 함
	 * - acquireKey 는 PROCESSING_MARKER 를 넣고
	 * - saveResult 가 실제 결과로 덮어쓰며
	 * - 이후 같은 키로 acquireKey 를 다시 시도하면 false (이미 존재)
	 */
	@Test
	@DisplayName("처리 후 멱등성 마커가 Redis 에 남아 재처리 차단")
	void idempotencyMarker_persistsAfterProcessing() {
		PaymentCompleteEvent event = new PaymentCompleteEvent("test-key-1", "user1", 1L, 30000L);

		consumer.handlePaymentComplete(event);

		// 컨슈머가 saveResult 로 마커를 남겼으므로 다시 acquireKey 시도하면 실패해야 한다.
		boolean reacquired = idempotencyService.acquireKey(
			"kafka:payment-complete:test-key-1", java.time.Duration.ofMinutes(1));
		assertThat(reacquired)
			.as("처리 완료된 paymentKey 는 다시 선점할 수 없어야 한다")
			.isFalse();
	}

	/**
	 * 시나리오 4: 처리 중 예외 발생 시 멱등성 키 해제
	 * - 알림 서비스가 예외를 던지면
	 * - releaseKey 로 키가 풀려서
	 * - Kafka 재시도(다음 이벤트 수신) 시 다시 처리할 수 있어야 한다.
	 */
	@Test
	@DisplayName("처리 중 예외 발생 시 멱등성 키 해제 → Kafka 재시도 시 재처리 가능")
	void exceptionDuringProcessing_releasesKey() {
		PaymentCompleteEvent event = new PaymentCompleteEvent("test-key-fail", "user-fail", 1L, 30000L);

		// 첫 호출: 알림 서비스가 예외 발생하도록 spy 에 stub
		org.mockito.Mockito.doThrow(new RuntimeException("notification fail"))
			.doNothing()  // 두 번째 호출은 정상
			.when(paymentNotificationService).notifyPaymentComplete("user-fail", 1L, 30000L);

		// 첫 호출: 예외 발생, 멱등성 키 해제됨
		try {
			consumer.handlePaymentComplete(event);
		} catch (RuntimeException ignored) {
			// 예상된 예외
		}

		// 두 번째 호출: 키가 해제되어 다시 처리되어야 함
		consumer.handlePaymentComplete(event);

		verify(paymentNotificationService, times(2))
			.notifyPaymentComplete("user-fail", 1L, 30000L);
	}
}
