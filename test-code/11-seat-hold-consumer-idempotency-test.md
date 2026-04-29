# 11. SeatHoldEventConsumer 멱등성 통합 테스트

> Kafka 컨슈머 멱등성 결함이 **한 곳에서 발견되면 유사한 모든 곳을 점검**해야 한다는 실무 교훈을 적용한 사례.

---

## 1. 발견 경위

`PaymentCompleteEventConsumer` 에서 멱등성 누락을 발견하고 수정한 뒤,
같은 패턴의 다른 Kafka 컨슈머인 `SeatHoldEventConsumer` 를 점검했다.

```java
// Before — 멱등성 체크 없음
@KafkaListener(topics = "ticketing.seat-hold-events", ...)
public void handleSeatHoldEvent(SeatHoldEvent event) {
    notificationService.addNotification(event.getUserId(), item);    // 중복 발송 위험
    sseNotificationService.sendNotification(event.getUserId(), item); // 중복 push 위험
}
```

**위험**: 같은 홀드 만료·예약 확정 알림이 Kafka 재전송 시 사용자에게 두 번 전달됨.

---

## 2. 멱등성 키 설계

`paymentKey` (단일 식별자)와 달리 `SeatHoldEvent` 는 하나의 `holdToken` 에서
**HOLD_EXPIRED** 와 **RESERVATION_CONFIRMED** 두 가지 이벤트가 각각 발행될 수 있다.

| 경우 | 멱등성 키 |
|------|----------|
| holdToken-A + EXPIRED | `kafka:seat-hold-event:holdToken-A:HOLD_EXPIRED` |
| holdToken-A + CONFIRMED | `kafka:seat-hold-event:holdToken-A:RESERVATION_CONFIRMED` |
| holdToken-B + EXPIRED | `kafka:seat-hold-event:holdToken-B:HOLD_EXPIRED` |

→ `holdToken + eventType` 조합으로 키를 구성해야 서로 다른 이벤트가 충돌하지 않는다.

---

## 3. 수정 코드

```java
String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + event.getHoldToken() + ":" + type.name();
if (!idempotencyService.acquireKey(idempotencyKey, IDEMPOTENCY_TTL)) {
    log.info("홀드 이벤트 중복 수신 - 알림 발송 스킵: type={}, holdToken={}", type, event.getHoldToken());
    return;
}
try {
    notificationService.addNotification(event.getUserId(), item);
    sseNotificationService.sendNotification(event.getUserId(), item);
    idempotencyService.saveResult(idempotencyKey, "DONE", IDEMPOTENCY_TTL);
} catch (RuntimeException e) {
    idempotencyService.releaseKey(idempotencyKey);
    throw e;
}
```

---

## 4. 검증한 4가지 시나리오

| # | 시나리오 | 검증 내용 |
|---|----------|----------|
| 1 | **중복 수신** | 같은 holdToken + HOLD_EXPIRED 3번 → 알림 1번 |
| 2 | **타입 독립성** | 같은 holdToken + EXPIRED / CONFIRMED → 각각 독립 처리 (총 2번) |
| 3 | **토큰 독립성** | 서로 다른 holdToken → 각각 독립 처리 |
| 4 | **예외 시 키 해제** | 처리 실패 → 키 해제 → Kafka 재시도 가능 |

### 시나리오 2가 핵심
```java
// 같은 holdToken 이라도 타입이 다르면 독립 처리되어야 한다
SeatHoldEvent expired   = expiredEvent(HOLD_TOKEN_1, "user1", 99L);
SeatHoldEvent confirmed = confirmedEvent(HOLD_TOKEN_1, "user1", 99L);

consumer.handleSeatHoldEvent(expired);
consumer.handleSeatHoldEvent(confirmed);

verify(notificationService, times(2)).addNotification(eq("user1"), any());
// EXPIRED 1번 + CONFIRMED 1번 = 총 2번 → OK
```

---

## 5. 실행 결과

```
SeatHoldEventConsumerIntegrationTest > 같은 holdToken + eventType 이벤트 3번 수신 → 알림은 정확히 1번만 발송  PASSED
SeatHoldEventConsumerIntegrationTest > 같은 holdToken, 다른 eventType → 각각 독립 처리 (EXPIRED + CONFIRMED = 2번) PASSED
SeatHoldEventConsumerIntegrationTest > 서로 다른 holdToken 이벤트 → 독립적으로 각 1번씩 처리 PASSED
SeatHoldEventConsumerIntegrationTest > 처리 중 예외 발생 시 멱등성 키 해제 → Kafka 재시도 시 재처리 가능 PASSED

4 tests passed, BUILD SUCCESSFUL
```

---

## 6. 면접 어필 포인트

### "하나 고치면 같은 문제가 다른 곳에도 있는지 확인했다"

> "PaymentCompleteEventConsumer 에서 멱등성 누락을 발견하고 수정한 뒤,
> 코드베이스에 같은 패턴의 컨슈머가 있는지 확인했습니다.
> SeatHoldEventConsumer 도 동일한 결함이 있었고, 같은 방식으로 수정했습니다.
> 버그를 하나 발견하면 유사한 코드 전체를 점검하는 게 실무 습관입니다."

### 멱등성 키 설계 차이 설명

> "결제 이벤트는 paymentKey 하나로 키를 만들면 됐지만,
> 홀드 이벤트는 하나의 holdToken 에서 EXPIRED 와 CONFIRMED 두 종류가 발행됩니다.
> type 을 키에 포함하지 않으면 EXPIRED 처리 후 CONFIRMED 가 중복으로 차단될 수 있어
> holdToken + eventType 조합으로 키를 설계했습니다."
