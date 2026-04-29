# 10. Kafka 컨슈머 멱등성 통합 테스트

> 메시지 큐 기반 비동기 처리에서 가장 자주 깨지는 영역 — **at-least-once 전달로 인한 중복 처리** —
> 를 자동화 테스트로 검증한 사례.
> 면접에서 "Kafka 써보셨어요?" "메시지 중복 어떻게 처리해요?" 라는 질문에 코드로 답할 수 있는 산출물.

---

## 1. 배경 — 왜 멱등성이 필요한가

Kafka 는 **at-least-once** 전달을 보장한다. 즉:

- 메시지는 **반드시 한 번 이상** 컨슈머에 전달된다
- 하지만 **두 번 이상** 전달될 수도 있다 (재시도, 리밸런스, offset commit 직전 장애 등)

```
컨슈머 시나리오 (장애 케이스)
  1. 메시지 수신
  2. 알림 발송 완료 (이메일 보냄)
  3. ✗ offset commit 직전에 컨슈머 크래시
  4. 재시작 후 같은 메시지 재수신
  5. 알림 재발송 → 사용자에게 같은 결제 완료 메일 2번
```

**결과**: 사용자 입장에서 "결제는 한 번 했는데 알림이 두 번 왔다" — 심각한 UX 문제이자 신뢰 손상.

---

## 2. 발견 경위 — 코드 리뷰로 찾은 결함

기존 `PaymentCompleteEventConsumer` 는 멱등성 체크가 **전혀 없었다**:

```java
// Before — 같은 메시지가 두 번 오면 알림도 두 번 발송됨
@KafkaListener(topics = "ticketing.payment-complete", ...)
public void handlePaymentComplete(PaymentCompleteEvent event) {
    paymentNotificationService.notifyPaymentComplete(
        event.getUserId(), event.getConcertId(), event.getAmount()
    );
}
```

Kafka 의 at-least-once 특성을 의식하지 않은 위험한 코드. **포트폴리오 검토 중 발견된 실제 결함이다.**

---

## 3. 수정 — Redis 기반 멱등성 키 적용

`paymentKey` 를 멱등성 키로 사용해 Redis 에 처리 마커를 저장한다.

```java
@KafkaListener(topics = "ticketing.payment-complete", ...)
public void handlePaymentComplete(PaymentCompleteEvent event) {
    String idempotencyKey = "kafka:payment-complete:" + event.getPaymentKey();

    // SET NX 로 처리 마커 선점. 이미 처리된 paymentKey 는 false.
    if (!idempotencyService.acquireKey(idempotencyKey, Duration.ofHours(24))) {
        logger.info("결제 완료 이벤트 중복 수신 - 알림 발송 스킵: paymentKey={}", event.getPaymentKey());
        return;
    }

    try {
        paymentNotificationService.notifyPaymentComplete(...);
        idempotencyService.saveResult(idempotencyKey, "DONE", Duration.ofHours(24));
    } catch (RuntimeException e) {
        // 처리 실패 시 키 해제 → Kafka 재시도 시 다시 시도 가능
        idempotencyService.releaseKey(idempotencyKey);
        throw e;
    }
}
```

### 핵심 설계 포인트
| 항목 | 이유 |
|------|------|
| **paymentKey 를 멱등성 키로** | 결제 단위 식별자 — 같은 결제는 어떤 경로로 와도 같은 키 |
| **acquireKey (Redis SET NX)** | 동시 중복 처리도 한 번만 통과. 여러 인스턴스가 같은 메시지를 받아도 안전. |
| **24시간 TTL** | 정상 운영 시 같은 paymentKey 재처리는 분 단위에서 발생 — 하루면 충분히 덮음 |
| **예외 시 releaseKey** | 알림 발송 실패 시 키를 풀어 Kafka 재시도(3회 + DLT)가 정상 동작하도록 |
| **DefaultErrorHandler 와 호환** | 예외를 다시 throw 해 Kafka 재시도/DLT 메커니즘 유지 |

---

## 4. 통합 테스트로 검증한 4가지 시나리오

`PaymentCompleteEventConsumerIntegrationTest` (Testcontainers Redis 사용)

| # | 시나리오 | 검증 내용 |
|---|----------|----------|
| 1 | **중복 수신** | 같은 paymentKey 이벤트 3번 수신 → 알림은 정확히 1번만 발송 |
| 2 | **독립 처리** | 서로 다른 paymentKey 는 각각 1번씩 알림 발송 (격리) |
| 3 | **마커 영속성** | 처리 후 Redis 에 마커가 남아 재선점 시도가 차단됨 |
| 4 | **예외 시 키 해제** | 알림 발송 실패 시 키 해제되어 재시도 시 재처리 가능 |

### 핵심 검증 코드 (시나리오 1)
```java
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
```

`@MockitoSpyBean` 으로 실제 `PaymentNotificationService` 를 감싸 호출 횟수를 검증한다.
실제 Redis 컨테이너의 `SET NX` 동작이 보장되는지 확인하는 게 핵심.

---

## 5. 실행 결과

```
PaymentCompleteEventConsumerIntegrationTest > 같은 paymentKey 이벤트 3번 수신 → 알림은 정확히 1번만 발송 PASSED
PaymentCompleteEventConsumerIntegrationTest > 서로 다른 paymentKey 는 독립적으로 처리 PASSED
PaymentCompleteEventConsumerIntegrationTest > 처리 후 멱등성 마커가 Redis 에 남아 재처리 차단 PASSED
PaymentCompleteEventConsumerIntegrationTest > 처리 중 예외 발생 시 멱등성 키 해제 → Kafka 재시도 시 재처리 가능 PASSED

4 tests passed, BUILD SUCCESSFUL
```

---

## 6. 왜 이 테스트가 의미 있나 (면접 어필 포인트)

1. **실제 결함을 발견하고 수정한 사례**
   - 기존 코드에 멱등성 체크가 없었음을 코드 리뷰로 발견
   - Kafka 의 at-least-once 특성을 의식한 수정
   - 통합 테스트로 회귀 방지

2. **Mock 으로는 검증 불가능한 영역**
   - Redis `SET NX` 의 원자성은 실제 Redis 가 있어야 검증 가능
   - Testcontainers 통합 테스트의 가치가 발휘되는 케이스

3. **운영 사고로 직결되는 영역**
   - "결제 완료 메일이 두 번 왔어요" 는 흔한 사고
   - 자동화 테스트로 이런 사고를 사전에 차단

4. **분산 시스템의 핵심 개념을 코드로 증명**
   - at-least-once vs exactly-once
   - 멱등성(idempotency)
   - Saga 보상과 다른 결의 안전장치

---

## 7. 면접 답변 스크립트

### Q. "Kafka 써보셨어요? 어떤 점을 신경 쓰셨어요?"
> "결제 완료 알림을 Kafka 비동기로 분리해서 응답 속도를 단축했습니다.
> Kafka 는 at-least-once 전달이라 같은 메시지가 두 번 들어올 수 있어,
> **컨슈머 멱등성**을 항상 신경 씁니다. 이 프로젝트에서는 paymentKey 를 멱등성 키로
> Redis 에 저장하고, 같은 키가 다시 들어오면 알림 발송을 스킵합니다."

### Q. "그게 실제로 동작하는지는 어떻게 확인하셨어요?"
> "Testcontainers Redis 기반 통합 테스트로 4가지 시나리오를 자동화했습니다:
> ① 같은 메시지 3번 → 알림 1번
> ② 다른 paymentKey → 독립 처리
> ③ 처리 후 마커 영속
> ④ 예외 시 키 해제 → Kafka 재시도 가능
> Mock 으로는 SET NX 의 원자성을 검증할 수 없어서 실제 Redis 로 돌렸습니다."

### Q. "처음부터 멱등성을 고려하셨나요?"
> "솔직히 처음 작성할 때는 빠뜨렸습니다.
> 포트폴리오 정리 중 코드 리뷰하면서 'Kafka at-least-once 인데 멱등성 체크가 없네' 하고 발견했고,
> Redis 기반 멱등성 추가 + 통합 테스트로 회귀 방지까지 마쳤습니다.
> 실수를 발견하고 자동화로 막은 사례라 면접에서 솔직하게 말씀드릴 수 있는 부분입니다."

---

## 8. 추가 보강 가능한 지점

| 개선 항목 | 비용 | 효과 |
|-----------|------|------|
| Kafka Producer 의 `enable.idempotence=true` | 설정 1줄 | 프로듀서 측 중복 발행 차단 (현재는 제어 안 함) |
| DLT (Dead Letter Topic) 처리 모니터링 | 1~2시간 | 멱등성으로 무시된 메시지가 진짜 실패인지 구분 |
| Outbox 패턴 도입 | 1일 | DB 트랜잭션과 Kafka 발행의 원자성 보장 (현재 outbox 테이블은 있으나 활용도 낮음) |
