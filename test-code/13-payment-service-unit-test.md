# 13. PaymentService 단위 테스트

> 결제 흐름의 핵심 분기와 Saga 보상 연동을 Mockito 단위 테스트로 검증한다.

---

## 1. 검증 대상 분기

`PaymentService` 는 `READY → APPROVED → COMPLETED` 3단계 흐름과 취소 경로를 가지며,
각 단계마다 검증해야 할 분기가 있다.

```
requestPayment  → READY Payment 생성
approvePayment  → POINT: 포인트 차감 / CARD: 토스 승인
completePayment → 예약 확정 (실패 시 Saga 보상 호출)
cancelPayment   → COMPLETED 는 취소 불가
```

---

## 2. 검증한 7가지 시나리오

`PaymentServiceTest` (Mockito, Docker 불필요)

| # | 메서드 | 시나리오 | 기대 결과 |
|---|--------|----------|----------|
| 1 | `approvePayment` | **포인트 잔액 부족** | 409 Conflict |
| 2 | `approvePayment` | **이미 APPROVED 재요청** | 멱등 응답 (에러 없음, 포인트 차감 없음) |
| 3 | `approvePayment` | **이미 CANCELED 결제 승인 시도** | 409 Conflict |
| 4 | `completePayment` | **예약 확정 실패 → Saga 보상 호출** | `compensateAfterReservationFailure` 호출 verify |
| 5 | `completePayment` | **예약 확정 성공 → 보상 미호출** | `compensateAfterReservationFailure` never 호출 verify |
| 6 | `cancelPayment` | **COMPLETED 결제 취소 시도** | 409 Conflict |
| 7 | `cancelPayment` | **다른 사용자 결제 취소 시도** | 409 소유자 불일치 |

---

## 3. 핵심 검증 코드

### Saga 보상 호출 여부 verify (가장 중요)
```java
@Test
@DisplayName("예약 확정 실패 시 Saga 보상 트랜잭션 호출 verify")
void completePayment_callsCompensation_whenReservationFails() {
    Payment payment = approvedPayment(30_000L);
    when(paymentRepository.findWithLockByPaymentKey(PAYMENT_KEY)).thenReturn(Optional.of(payment));
    when(reservationService.confirm(any(), eq(USER_ID)))
        .thenThrow(new RuntimeException("예약 확정 실패 시뮬레이션"));

    assertThatThrownBy(() -> paymentService.completePayment(PAYMENT_KEY, USER_ID))
        .isInstanceOf(RuntimeException.class);

    // 보상 서비스가 반드시 호출되어야 한다
    verify(paymentCompensationService)
        .compensateAfterReservationFailure(payment.getId());
}
```

### 멱등성 검증 — 포인트 차감이 한 번만 일어나는지
```java
@Test
@DisplayName("이미 APPROVED 결제 재요청 → 멱등 응답 (에러 없음)")
void approvePayment_idempotent_whenAlreadyApproved() {
    Payment payment = approvedPayment(30_000L);
    when(paymentRepository.findWithLockByPaymentKey(PAYMENT_KEY)).thenReturn(Optional.of(payment));

    var response = paymentService.approvePayment(PAYMENT_KEY, USER_ID);

    assertThat(response.getStatus()).isEqualTo(PaymentStatus.APPROVED);
    verify(usersRepository, never()).findWithLockByUsername(any()); // 포인트 차감 없음
}
```

---

## 4. 단위 테스트와 통합 테스트의 역할 분담

| 검증 항목 | 단위 테스트 (이 파일) | 통합 테스트 |
|-----------|----------------------|------------|
| 보상이 **호출되는지** | ✅ `verify()` 로 확인 | - |
| 보상이 **실제로 동작하는지** (포인트 환불, CANCELED 전환) | - | `PaymentCompensationIntegrationTest` |

**역할이 명확히 분리되어 있다.** 단위 테스트는 "연결이 맞는가"를, 통합 테스트는 "실제 DB 에서 동작하는가"를 검증한다.

---

## 5. 면접 답변 스크립트

### Q. "결제 서비스 테스트는 어떻게 하셨어요?"

> "Mockito 단위 테스트와 Testcontainers 통합 테스트를 역할 분리해서 작성했습니다.
> 단위 테스트에서는 포인트 부족·멱등성·소유자 검증 같은 **비즈니스 분기**와,
> 예약 실패 시 보상 서비스가 호출되는지 `verify()` 로 확인합니다.
> 실제 보상이 DB 에서 동작하는지는 별도 Testcontainers 통합 테스트로 검증합니다."

### Q. "보상 트랜잭션 호출을 왜 단위 테스트로 verify 했나요?"

> "통합 테스트만 있으면 '보상 서비스가 실제로 올바르게 작동한다'는 건 알아도,
> 'PaymentService.completePayment 가 실패 시 보상을 반드시 호출하는가'라는
> 연결 지점을 빠르게 확인할 수 없습니다.
> `verify(paymentCompensationService).compensateAfterReservationFailure(id)` 한 줄이
> 그 연결이 끊어지지 않도록 자동으로 지켜줍니다."
