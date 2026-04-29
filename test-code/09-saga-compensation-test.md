# 09. Saga 보상 트랜잭션 통합 테스트

> 분산 트랜잭션의 정합성을 자동화 테스트로 증명한 사례.
> 면접에서 "Saga 패턴 써보셨어요?" "보상 트랜잭션이 실제로 동작하는 거 어떻게 확인했어요?" 라는 질문에 코드로 답할 수 있는 산출물.

---

## 1. 배경 — 왜 보상 트랜잭션이 필요한가

이 프로젝트의 결제 흐름은 **3단계**로 분리되어 있다:

```
1단계  approvePayment()    → POINT 차감 OR 토스 PG 승인 → APPROVED
2단계  completePayment()   → DB 에 Reservation 저장 → COMPLETED
3단계  Kafka 이벤트 발행   → 결제 완료 알림
```

**문제 상황**:
1단계에서 포인트가 차감되고 별도 트랜잭션으로 커밋된 뒤,
2단계 예약 확정에서 예외(좌석 충돌, DB 장애, 동시성 race 등)가 발생하면?

- `@Transactional` 롤백은 **2단계 DB 변경**만 되돌린다
- 1단계의 **포인트 차감은 이미 커밋되어 있어 롤백 불가**
- 결과: **포인트는 빠졌는데 예약은 없는** 일관성 깨진 상태 발생

**해결**: 2단계 실패를 감지하면 **별도 트랜잭션**으로 1단계를 되돌리는 코드(보상 트랜잭션)를 실행한다.

```
1단계 커밋 ─── 2단계 실패 ─── 보상 트랜잭션 (REQUIRES_NEW)
   ↓                              ↓
포인트 -30,000                  포인트 +30,000 환불
                                결제 CANCELED 마크
```

---

## 2. 핵심 구현 (`PaymentCompensationService`)

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void compensateAfterReservationFailure(Long paymentId) {
    Payment payment = paymentRepository.findWithLockById(paymentId).orElseThrow(...);

    if (payment.getStatus() == PaymentStatus.CANCELED) return;     // 멱등성
    if (payment.getStatus() != PaymentStatus.APPROVED) return;     // 잘못된 호출 보호

    if (payment.getPaymentMethod() == PaymentMethod.POINT) {
        refundPoints(payment.getUserId(), payment.getAmount());    // 포인트 환불
    }
    payment.setStatus(PaymentStatus.CANCELED);
    payment.setCanceledAt(LocalDateTime.now());
}
```

### 핵심 설계 포인트
| 항목 | 설명 |
|------|------|
| `Propagation.REQUIRES_NEW` | outer 트랜잭션과 **완전히 독립된 새 트랜잭션**으로 실행. outer 가 롤백되어도 보상 결과는 별도 커밋되어 살아남는다. |
| `findWithLockById` (PESSIMISTIC_WRITE) | 동시에 여러 보상 호출이 와도 같은 결제를 중복 수정하지 못하게 비관적 락. |
| 상태 체크 (CANCELED, APPROVED 외 스킵) | 멱등성·잘못된 호출 방어. 같은 보상이 두 번 호출돼도 안전. |

---

## 3. 통합 테스트로 검증한 4가지 시나리오

`PaymentCompensationIntegrationTest` (Testcontainers MySQL 사용)

| # | 시나리오 | 검증 내용 | 왜 중요한가 |
|---|----------|----------|------------|
| 1 | **APPROVED 결제 보상** | POINT 환불 + 결제 CANCELED 전환 + canceledAt 기록 | 정상 흐름. 보상이 실제로 작동하는지 |
| 2 | **재보상 멱등성** | 이미 CANCELED 인 결제에 보상 재호출 → 포인트 중복 환불 안 됨 | 네트워크 재시도·스케줄러 중복 실행으로 같은 보상이 두 번 호출될 수 있음 |
| 3 | **READY 상태 보호** | APPROVED 가 아닌 결제는 보상 스킵 → 포인트·상태 변화 없음 | 잘못된 호출(승인 전 보상)로부터 데이터 무결성 보호 |
| 4 | **CARD 결제 보상** | 포인트 환불 없이 상태만 CANCELED | 결제 수단별 보상 로직 분기 검증 |

### 실제 검증 코드 (시나리오 2)
```java
@Test
@DisplayName("이미 CANCELED 인 결제 재보상 호출 → 멱등 보장 (포인트 중복 환불 없음)")
void compensate_isIdempotent_whenAlreadyCanceled() {
    Users user = createUser(INITIAL_POINT - PAYMENT_AMOUNT);
    Payment payment = createApprovedPointPayment(user.getUsername(), PAYMENT_AMOUNT);

    paymentCompensationService.compensateAfterReservationFailure(payment.getId());
    long pointAfterFirst = usersRepository.findByUsername(TEST_USERNAME).orElseThrow().getPoint();
    assertThat(pointAfterFirst).isEqualTo(INITIAL_POINT);

    // 두 번째 호출
    paymentCompensationService.compensateAfterReservationFailure(payment.getId());
    long pointAfterSecond = usersRepository.findByUsername(TEST_USERNAME).orElseThrow().getPoint();
    assertThat(pointAfterSecond)
        .as("두 번째 보상 호출 후에도 포인트가 추가 환불되지 않아야 한다")
        .isEqualTo(INITIAL_POINT);
}
```

---

## 4. 실행 결과

```
PaymentCompensationIntegrationTest > compensate_refundsPointsAndCancelsPayment   PASSED
PaymentCompensationIntegrationTest > compensate_isIdempotent_whenAlreadyCanceled PASSED
PaymentCompensationIntegrationTest > compensate_skips_whenStatusIsNotApproved    PASSED
PaymentCompensationIntegrationTest > compensate_cardPayment_onlyCancelsStatus    PASSED

4 tests passed, BUILD SUCCESSFUL
```

---

## 5. 왜 이 테스트가 의미 있나 (면접 어필 포인트)

1. **단위 테스트로는 절대 검증 불가능한 영역**
   `REQUIRES_NEW` 트랜잭션 분리가 실제로 동작하는지는 **실제 MySQL 트랜잭션 격리**가 있어야만 확인된다.
   Mock 으로는 검증 불가. → Testcontainers 통합 테스트의 진가가 발휘되는 케이스.

2. **분산 트랜잭션의 핵심 개념을 코드로 증명**
   - Saga 패턴 (분산 트랜잭션 보상)
   - REQUIRES_NEW (트랜잭션 경계 분리)
   - 멱등성 (재시도 안전성)
   - 비관적 락 (동시성 제어)

3. **실무에서 가장 자주 깨지는 부분**
   결제·예약 시스템에서 "포인트는 빠졌는데 예약이 없다" 같은 데이터 정합성 사고는 흔히 발생한다.
   이 테스트는 그런 사고를 **자동으로 막아주는 안전장치** 역할을 한다.

---

## 6. 면접 답변 스크립트

### Q. "Saga 패턴 써보셨어요?"
> "네. 결제 흐름이 [포인트 차감 → 예약 확정 → 알림 발행] 3단계로 분리되어 있는데,
> 2단계 실패 시 1단계를 되돌리는 보상 트랜잭션을 `PaymentCompensationService` 에 구현했습니다.
> 핵심은 outer 트랜잭션과 분리된 `REQUIRES_NEW` 로 보상을 커밋하는 것입니다.
> 그래야 outer 가 롤백되어도 보상 결과가 살아남습니다."

### Q. "보상 트랜잭션이 실제로 동작하는 거 어떻게 확인했어요?"
> "Testcontainers 기반 통합 테스트로 4가지 시나리오를 자동화했습니다:
> ① 정상 보상 → 포인트 환불 + 결제 CANCELED
> ② 재호출 멱등성 → 포인트 중복 환불 방지
> ③ READY 상태 보호 → 잘못된 호출에 데이터 무결성 유지
> ④ CARD 결제 → 포인트 미환불, 상태만 CANCELED
> 단위 테스트로는 검증 불가능해서 **실제 MySQL 트랜잭션**으로 격리 동작까지 확인했습니다."

### Q. "멱등성은 왜 신경 썼어요?"
> "보상 호출은 네트워크 재시도, 스케줄러 중복 실행 등으로 **같은 paymentId 에 대해 두 번 호출될 수 있습니다**.
> 만약 멱등하지 않으면 포인트가 두 번 환불되어 사용자가 실제 결제 금액보다 많은 포인트를 보유하게 됩니다.
> 그래서 보상 시작 시 `payment.getStatus() == CANCELED` 체크를 추가하고, 통합 테스트로 검증했습니다."
