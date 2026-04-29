package com.inyoung.ticketing.hold.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import com.inyoung.ticketing.common.idempotency.IdempotencyService;
import com.inyoung.ticketing.notification.service.NotificationService;
import com.inyoung.ticketing.notification.service.SseNotificationService;
import com.inyoung.ticketing.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * SeatHoldEventConsumer 멱등성 통합 테스트.
 *
 * <p><b>발견 경위</b>: PaymentCompleteEventConsumer 에서 멱등성 누락을 발견하고
 * 동일 패턴으로 SeatHoldEventConsumer 를 검토하니 같은 결함이 존재.
 * 홀드 만료·예약 확정 알림이 Kafka 재전송 시 사용자에게 중복 발송될 수 있었다.
 *
 * <p><b>검증 시나리오</b>:
 * <ol>
 *     <li>같은 holdToken + eventType 의 이벤트가 중복 수신 → 알림 1번만 발송</li>
 *     <li>같은 holdToken 이라도 eventType 이 다르면 독립 처리 (EXPIRED ≠ CONFIRMED)</li>
 *     <li>다른 holdToken 은 각각 독립 처리</li>
 *     <li>예외 시 멱등성 키 해제 → Kafka 재시도 가능</li>
 * </ol>
 */
class SeatHoldEventConsumerIntegrationTest extends IntegrationTestBase {

	@Autowired private SeatHoldEventConsumer consumer;
	@Autowired private IdempotencyService idempotencyService;

	@MockitoSpyBean private NotificationService notificationService;
	@MockitoSpyBean private SseNotificationService sseNotificationService;

	private static final String HOLD_TOKEN_1 = "hold-token-test-001";
	private static final String HOLD_TOKEN_2 = "hold-token-test-002";

	@BeforeEach
	void setUp() {
		idempotencyService.releaseKey("kafka:seat-hold-event:" + HOLD_TOKEN_1 + ":HOLD_EXPIRED");
		idempotencyService.releaseKey("kafka:seat-hold-event:" + HOLD_TOKEN_1 + ":RESERVATION_CONFIRMED");
		idempotencyService.releaseKey("kafka:seat-hold-event:" + HOLD_TOKEN_2 + ":HOLD_EXPIRED");
		idempotencyService.releaseKey("kafka:seat-hold-event:hold-token-fail:HOLD_EXPIRED");
	}

	/**
	 * 시나리오 1: 같은 holdToken + HOLD_EXPIRED 이벤트를 3번 수신
	 * - 알림 저장(addNotification)은 정확히 1번만 호출되어야 한다.
	 */
	@Test
	@DisplayName("같은 holdToken + eventType 이벤트 3번 수신 → 알림은 정확히 1번만 발송")
	void duplicateEvent_notifyOnlyOnce() {
		SeatHoldEvent event = expiredEvent(HOLD_TOKEN_1, "user1", 99L);

		consumer.handleSeatHoldEvent(event);
		consumer.handleSeatHoldEvent(event);
		consumer.handleSeatHoldEvent(event);

		verify(notificationService, times(1)).addNotification(
			org.mockito.ArgumentMatchers.eq("user1"),
			org.mockito.ArgumentMatchers.any()
		);
	}

	/**
	 * 시나리오 2: 같은 holdToken 이라도 eventType 이 다르면 독립 처리
	 * - HOLD_EXPIRED → 알림 1번
	 * - RESERVATION_CONFIRMED → 알림 1번 (총 2번)
	 * holdToken 은 같아도 이벤트 종류가 다르므로 멱등성 키가 달라 각각 처리된다.
	 */
	@Test
	@DisplayName("같은 holdToken, 다른 eventType → 각각 독립 처리 (EXPIRED + CONFIRMED = 2번)")
	void sameToken_differentType_processedIndependently() {
		SeatHoldEvent expired = expiredEvent(HOLD_TOKEN_1, "user1", 99L);
		SeatHoldEvent confirmed = confirmedEvent(HOLD_TOKEN_1, "user1", 99L);

		consumer.handleSeatHoldEvent(expired);
		consumer.handleSeatHoldEvent(confirmed);

		verify(notificationService, times(2)).addNotification(
			org.mockito.ArgumentMatchers.eq("user1"),
			org.mockito.ArgumentMatchers.any()
		);
	}

	/**
	 * 시나리오 3: 서로 다른 holdToken 이벤트는 독립 처리
	 */
	@Test
	@DisplayName("서로 다른 holdToken 이벤트 → 독립적으로 각 1번씩 처리")
	void differentTokens_processedIndependently() {
		SeatHoldEvent event1 = expiredEvent(HOLD_TOKEN_1, "user1", 99L);
		SeatHoldEvent event2 = expiredEvent(HOLD_TOKEN_2, "user2", 100L);

		consumer.handleSeatHoldEvent(event1);
		consumer.handleSeatHoldEvent(event2);

		verify(notificationService, times(1)).addNotification(
			org.mockito.ArgumentMatchers.eq("user1"),
			org.mockito.ArgumentMatchers.any()
		);
		verify(notificationService, times(1)).addNotification(
			org.mockito.ArgumentMatchers.eq("user2"),
			org.mockito.ArgumentMatchers.any()
		);
	}

	/**
	 * 시나리오 4: 처리 중 예외 발생 시 멱등성 키 해제
	 * - 첫 호출: 예외 발생 → 키 해제
	 * - 두 번째 호출: 키가 없으므로 재처리 가능
	 */
	@Test
	@DisplayName("처리 중 예외 발생 시 멱등성 키 해제 → Kafka 재시도 시 재처리 가능")
	void exceptionDuringProcessing_releasesKey() {
		SeatHoldEvent event = expiredEvent("hold-token-fail", "user-fail", 99L);

		org.mockito.Mockito.doThrow(new RuntimeException("notification fail"))
			.doNothing()
			.when(notificationService).addNotification(
				org.mockito.ArgumentMatchers.eq("user-fail"),
				org.mockito.ArgumentMatchers.any()
			);

		try {
			consumer.handleSeatHoldEvent(event);
		} catch (RuntimeException ignored) {}

		// 키가 해제되어 두 번째 호출이 처리됨
		consumer.handleSeatHoldEvent(event);

		verify(notificationService, times(2)).addNotification(
			org.mockito.ArgumentMatchers.eq("user-fail"),
			org.mockito.ArgumentMatchers.any()
		);

		// 두 번째 성공 후에는 멱등성 키가 남아 재선점 불가
		boolean reacquired = idempotencyService.acquireKey(
			"kafka:seat-hold-event:hold-token-fail:HOLD_EXPIRED",
			java.time.Duration.ofMinutes(1)
		);
		assertThat(reacquired).isFalse();
	}

	private SeatHoldEvent expiredEvent(String holdToken, String userId, Long seatId) {
		return new SeatHoldEvent(
			SeatHoldEventType.HOLD_EXPIRED,
			holdToken, 1L, seatId, userId,
			Instant.now().plusSeconds(600), Instant.now()
		);
	}

	private SeatHoldEvent confirmedEvent(String holdToken, String userId, Long seatId) {
		return new SeatHoldEvent(
			SeatHoldEventType.RESERVATION_CONFIRMED,
			holdToken, 1L, seatId, userId,
			Instant.now().plusSeconds(600), Instant.now()
		);
	}
}
