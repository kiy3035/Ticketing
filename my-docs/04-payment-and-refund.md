# 04. 결제·환불 (상세)

결제 흐름(request → approve → complete)과, **Saga 보상 트랜잭션(REQUIRES_NEW)**, 공연 취소 환불 배치까지.

---

## 1. 결제 흐름 (`PaymentService`)

### requestPayment — `POST /api/payments/request`

```java
@Transactional
public PaymentResponse requestPayment(PaymentRequest request, String userId)
```

1. holdToken 검증 (소유자, 존재)
2. **홀드 TTL 연장**: `holdStore.extendHoldTtl(holdToken, 20분)` — 결제 화면에 머무는 동안 만료 방지
3. 동일 holdToken으로 이미 Payment 있으면 그걸 반환 (재요청 안전 / `findWithLockByHoldToken` 비관적 락)
4. Seat 조회, **공연 CANCELLED 검사**
5. `Payment` 생성: `READY` 상태, paymentMethod(POINT/CARD), amount=seat.price
6. CARD면 `orderId = "TICKET_" + paymentKey 24자리` 부여 (토스 위젯 requestPayment 에 전달)

### approvePayment / approvePaymentWithOption — `POST /api/payments/{paymentKey}/approve`

```java
@Transactional
public PaymentResponse approvePaymentWithOption(String paymentKey, String userId, CardApproveRequest cardRequest)
```

1. `Payment` `PESSIMISTIC_WRITE` (`findWithLockByPaymentKey`) → 소유자 검증
2. 상태 가드:
   - APPROVED/COMPLETED → 그대로 반환 (멱등)
   - CANCELED → 409
3. **CARD**:
   - body(`paymentKey, orderId, amount`) 검증, 우리 Payment와 일치 확인
   - `tossPaymentsClient.confirmPayment(paymentKey, orderId, amount)` 호출
   - `tossPaymentKey` 저장, `status = APPROVED`, `approvedAt`
4. **POINT**:
   - `Users` `PESSIMISTIC_WRITE` (`findWithLockByUsername`) → 잔액 검사
   - 부족 시 409, 충분하면 차감 후 `status = APPROVED`

### completePayment — `POST /api/payments/{paymentKey}/complete`

```java
@Transactional
public PaymentResponse completePayment(String paymentKey, String userId)
```

1. `Payment` PESSIMISTIC_WRITE + 소유자 검증
2. COMPLETED → 멱등 반환, APPROVED 아니면 409
3. **`ReservationService.confirm()` 호출**:
   - 성공 시: 예약·좌석·outbox row 한 트랜잭션으로 커밋
   - 실패 시 catch → **Saga 보상**:
     ```java
     paymentCompensationService.compensateAfterReservationFailure(payment.getId());
     throw e;  // 원래 예외 재던짐
     ```
4. `Payment.status = COMPLETED`, `completedAt`, `reservationId` 저장
5. `paymentCompletedCounter.increment()`, `paymentCompleteTimer` 기록
6. **Kafka 직접 발행**: `paymentCompleteEventPublisher.publishPaymentComplete(...)` → `ticketing.payment-complete` 토픽 (이메일/SMS 알림용)

**정리**: 예약 확정은 **complete 단계에서만** 발생. 별도 "예약 확정 API" 없음.

---

## 2. Saga 보상 — `PaymentCompensationService.compensateAfterReservationFailure`

### 왜 필요한가?
```
1단계 approvePayment: 포인트 차감 → APPROVED (이미 커밋됨)
2단계 completePayment → ReservationService.confirm() 안에서 예외!
       └ outer @Transactional 롤백 → 2단계 DB 변경은 롤백되지만
         1단계는 별도 트랜잭션이라 포인트 차감은 그대로 → 돈만 빠짐
```

### 어떻게 해결?
```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void compensateAfterReservationFailure(Long paymentId) {
    Payment payment = paymentRepository.findWithLockById(paymentId)...;
    if (payment.getStatus() == CANCELED) return;  // 멱등
    if (payment.getStatus() != APPROVED) return;  // 보상 대상 아님

    if (payment.getPaymentMethod() == PaymentMethod.POINT) {
        refundPoints(payment.getUserId(), payment.getAmount());
    }
    payment.setStatus(PaymentStatus.CANCELED);
    payment.setCanceledAt(now);
}
```

**REQUIRES_NEW의 의미**:
- outer 트랜잭션이 롤백 예정이어도 보상 트랜잭션은 별도로 시작·커밋된다.
- 따라서 outer 가 롤백되어도 "포인트 환불 + 결제 CANCELED"는 DB에 남는다.

**CARD 결제**: 샌드박스 환경에서는 DB 상태만 CANCELED. 운영에서는 Toss 취소 API 호출 필요(TODO).

---

## 3. 결제 취소 (사용자 API) — `cancelPayment`

- **API**: `POST /api/payments/{paymentKey}/cancel`
- **로직**:
  - COMPLETED → 409 (이미 완료된 결제는 사용자가 직접 취소 불가)
  - APPROVED + POINT → 포인트 환불 후 CANCELED
  - APPROVED + CARD → DB만 CANCELED (Toss 취소 API 미호출 — 샌드박스)
  - READY 등 → 그냥 CANCELED

---

## 4. 공연 취소 환불 배치 — `RefundForCancelledConcertScheduler`

**실행 주기**: `fixedDelay = ${ticketing.refund.interval-ms:300000}` (5분)
**분산 락**: `lock:batch:refund` TTL 360초

**한 번 돌 때**:
1. `ConcertRepository.findByStatus(CANCELLED)` — 취소된 공연들
2. 공연별로 `Payment.status = COMPLETED` 페이징 조회 (`batchSize=50`)
3. **Virtual Thread 풀**(`Executors.newVirtualThreadPerTaskExecutor`)로 청크 내 결제 건 병렬 처리
4. 각 건 `paymentService.refundCompletedPaymentForCancelledConcert(paymentId)` 호출

### `refundCompletedPaymentForCancelledConcert` 안에서 순서

```java
@Transactional
public boolean refundCompletedPaymentForCancelledConcert(Long paymentId) {
    Payment payment = paymentRepository.findWithLockById(paymentId).orElse(null);
    if (payment == null) return false;
    if (payment.getStatus() == CANCELED) return true;  // 이미 취소
    if (payment.getStatus() != COMPLETED) return false;  // 완료된 결제만 대상

    // 1) 예약 취소·좌석 해제 (실패 시 환불 진행 안 함)
    if (payment.getReservationId() != null) {
        reservationService.cancelReservationForRefund(payment.getReservationId());
    }

    // 2) 포인트 환불 (POINT만) — 실패 시 false 반환해 다음 배치에서 재시도
    if (payment.getPaymentMethod() == PaymentMethod.POINT) {
        try { refundPoints(payment.getUserId(), payment.getAmount()); }
        catch (Exception e) { return false; }
    }

    // 3) 결제 CANCELED
    payment.setStatus(CANCELED);
    payment.setCanceledAt(now);
    return true;
}
```

**왜 이 순서?**
- 예약 취소·좌석 해제를 먼저 → 실패해도 포인트는 그대로 (사용자가 좌석은 가지고 있음)
- 포인트 환불 실패 시 결제 상태도 그대로 → 다음 배치에서 같은 paymentId 다시 시도 (idempotent)

**카운터·로깅**:
- `ticketing_refund_processed_total` Counter
- `ticketing_batch_run_duration_seconds{batch=refund}` Timer
- `ticketing_batch_run_total{batch=refund,status=success|failure}`

---

## 5. 결제 완료 알림 (Kafka 컨슈머)

- **Producer**: `PaymentCompleteEventPublisher` → `ticketing.payment-complete` (직접 send, outbox 미사용)
- **Consumer**: `PaymentCompleteEventConsumer` (group `ticketing-payment-notification`)
- **VT 적용**: `KafkaConfig.virtualThreadExecutor("kafka-payment-")` 로 SMTP/SMS I/O 대기 중 carrier thread 반납
- **`PaymentNotificationService.notifyPaymentComplete()`**:
  - `Users.notiType` 가 `email` → `EmailService.sendPaymentCompleteEmail()` (Spring Mail/SMTP)
  - `sms` → `SmsService.sendPaymentCompleteSms()` (Solapi API)
  - phone 없으면 email로 fallback
- **알림 실패가 결제 프로세스를 막지 않도록** 모든 예외를 catch + 로깅만

---

## 6. 테스트 시 참고

- **공연 취소 + 환불** 테스트: 판매자로 공연 취소 → 1주기(기본 5분) 대기 또는
  `ticketing.refund.interval-ms=10000`으로 줄여 재기동 → ~10초 후 배치가 돌고
  `payment.canceled_at`, `reservation.status=CANCELLED`, `seat.status=AVAILABLE`, `users.point` 복구 확인 가능.
- **Saga 보상** 검증: `ReservationService.confirm()`에서 강제로 예외 던지게 한 뒤 POINT 결제 흐름 → 포인트 잔액 복구 + payment CANCELED 확인.
