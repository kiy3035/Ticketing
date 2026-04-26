# 트랜잭션 / 일관성

---

### 🟡 Q1. 예약 확정(`confirm`) 시 DB 와 Redis·Kafka 는 어떤 순서로 움직이나요?

**A.** 한 트랜잭션 안에서:
1. `seat.status = RESERVED` (DB save)
2. `Reservation` 생성 (DB save)
3. `applicationEventPublisher.publishEvent(ReservationConfirmedEvent)` — AFTER_COMMIT 이라 아직 실행 안 됨
4. `kafkaOutboxService.enqueueSeatHoldEvent(RESERVATION_CONFIRMED)` — `kafka_outbox` 테이블 INSERT (같은 트랜잭션)

→ **트랜잭션 커밋 (또는 롤백)** ←

5. `ReservationConfirmedEventListener` (`@TransactionalEventListener(AFTER_COMMIT)`) → **`holdStore.releaseHold()`** + 잔여석 캐시 evict 만 수행
6. `KafkaOutboxPublishScheduler` (별도 스케줄, 500ms 주기) → outbox 행을 읽어 `kafkaTemplate.send().get(15s)` → 성공 시 행 **DELETE**

> **🔴 Q1-1. 왜 Kafka 를 트랜잭션 안에서 직접 send 하지 않나요?**
> **A.** 두 가지 위험:
> - **이중 쓰기 문제**: send 직후 트랜잭션이 롤백되면 "메시지는 나갔는데 DB 는 없음" → 유령 이벤트
> - **응답 지연**: 브로커 지연이 길면 DB 커밋도 지연 → 사용자 응답 시간 악화
>
> outbox 는 **로컬 DB 트랜잭션 한 번** 으로 "발행할 의무" 를 남기고, 비동기 워커가 실제 발행. **at-least-once** 보장 (consumer 멱등 처리 필요).

> **🔴 Q1-2. outbox 발행이 계속 실패하면?**
> **A.** `publishAttempts` 가 `max-publish-attempts(25)` 초과 시 `status = FAILED` 로 남겨 자동 재시도 중단. 운영 알람·수동 재처리 대상이 됩니다. 예약·좌석 DB 상태는 이미 확정이라 **강한 일관성(예약 데이터)** 은 유지되고, **다운스트림(알림 등)** 만 지연·누락 가능 — 이 트레이드오프를 명시적으로 받아들였습니다.

---

### 🟡 Q2. 결제 완료 플로우의 트랜잭션 경계는?

**A.** `PaymentService.completePayment()` 가 `@Transactional` 로:
1. `Payment` PESSIMISTIC_WRITE 조회 + 검증
2. `ReservationService.confirm()` 호출 (위 Q1 흐름)
3. `payment.status = COMPLETED`, `completedAt`, `reservationId` 저장

이후 `PaymentCompleteEventPublisher` 가 Kafka `ticketing.payment-complete` 토픽에 **직접 send** (outbox 미사용 — 알림 누락이 비즈니스 치명적이지 않음).

`PaymentCompleteEventConsumer` 가 받아 `PaymentNotificationService` 로 이메일/SMS 분기 발송. 알림 실패는 try-catch 로 흡수해 결제 프로세스에 영향 없음.

> **🟡 Q2-1. 멱등은 어디까지 적용했나요?**
> **A.** 두 단계:
> - **HTTP**: `@Idempotent` AOP + `Idempotency-Key` 헤더 → Redis `__PROCESSING__` 마커 + 결과 캐시 → 동일 키 재요청 시 캐시된 응답 반환 (이중 결제 POST 방지)
> - **Kafka 컨슈머**: 비즈니스 상태 가드로 멱등. 예: `SeatHoldEventConsumer` 는 동일 이벤트 재수신해도 `notify:user:{userId}` List 에 LPUSH+LTRIM 50건 → 중복 알림이 한도 안에서 자연 정리됨.

---

### 🟡 Q3. 보상 트랜잭션이나 환불은 어떻게 하나요?

**A.** 두 가지 경로:

**1) 결제 완료 중 예약 확정 실패 → Saga 보상 (동기, REQUIRES_NEW)**
- `PaymentService.completePayment()` 에서 `reservationService.confirm()` 실패 catch
- `PaymentCompensationService.compensateAfterReservationFailure(paymentId)` 호출 (`@Transactional(REQUIRES_NEW)`)
- POINT 결제면 포인트 환불, 결제 CANCELED → outer 트랜잭션 롤백과 무관하게 별도 커밋

**2) 콘서트 취소 → 배치 환불 (비동기, 5분 주기)**
- 판매자 취소 API 는 `Concert.status = CANCELLED` 만 저장
- `RefundForCancelledConcertScheduler` (5분 주기) 가 CANCELLED 공연의 COMPLETED 결제 페이징 조회
- VT 풀로 병렬 `paymentService.refundCompletedPaymentForCancelledConcert(paymentId)` — 예약/좌석 복구 + 포인트 환불 + 결제 CANCELED

> **🟡 Q3-1. 스케줄러가 두 번 돌면 중복 환불되나요?**
> **A.** 두 가지 보호:
> - `lock:batch:refund` 분산 락 (TTL 360초) 으로 한 인스턴스만 실행
> - `refundCompletedPaymentForCancelledConcert` 안에서 `payment.status` 가드: 이미 CANCELED 면 그대로 return (멱등)

> **🔴 Q3-2. 왜 Saga 오케스트레이터를 쓰지 않았나요?**
> **A.** 보상이 필요한 경계가 **결제→예약 1곳뿐** 이라서 별도 조정자 서비스를 두는 건 과한 설계입니다. 단순 try-catch + REQUIRES_NEW 로 충분하고, 향후 보상 경계가 늘어나면 코레오그래피 → 오케스트레이터로 진화 가능.

---

### 🔴 Q4. 강한 일관성 vs 최종 일관성을 어떻게 나눴나요?

**A.**
| 카테고리 | 방식 | 이유 |
|----------|------|------|
| **강한 일관성** | 단일 DB 트랜잭션 + 비관적 락 + UNIQUE 제약 | "같은 좌석 두 번 팔림"은 허용 불가 |
| 적용: 예약 row, 좌석 status, 결제 상태, 포인트 잔액 | | |
| **최종 일관성** | Kafka 비동기 + outbox 재시도 + DLT | "예약은 됐는데 이메일이 1초 늦었다"는 허용 가능 |
| 적용: 알림(이메일/SMS), SSE 푸시, 일부 이벤트 | | |
| **읽기 일관성 완화** | Redis 캐시 (콘서트 목록 5분, 잔여석 2초) | 화면 갱신 약간의 지연 허용 |

> **🔴 Q4-1. Redis 홀드와 DB 좌석이 어긋나면?**
> **A.** 이중 방어로 어긋나도 결과는 안전합니다.
> - Redis TTL 로 휘발성 선점만 풂
> - 예약 확정 시 DB 에서 `AVAILABLE` 인 좌석만 `RESERVED` 로 (좌석 락 + status 검증)
> - Lua 로 홀드 생성 시 `concertId` 도 함께 검증해 다른 공연 좌석 치환 차단
> - `ReservationConfirmedEventListener` 의 `releaseHold` 가 실패해도 다음 cleanup 스케줄러가 정리

---

### 🔴 Q5. 분산 환경에서 "한 번만 실행"이 필요한 작업은?

**A.** 5종 스케줄러 모두. 각각 분산 락 키로 보호:
- `QueueProcessingScheduler` → `lock:batch:queue-process` (15s)
- `QueueCleanupScheduler` → `lock:batch:queue-cleanup`
- `HoldCleanupScheduler` → `lock:batch:hold-cleanup` (90s)
- `RefundForCancelledConcertScheduler` → `lock:batch:refund` (360s — 가장 길게 도는 배치)
- `KafkaOutboxPublishScheduler` → `lock:batch:kafka-outbox` (120s)

`RedisLockService.tryLock` (SETNX + UUID 토큰 + TTL) → unlock Lua (본인 토큰일 때만 DEL).

> **🔴 Q5-1. 락 TTL 이 짧으면 어떤 일이 생기나요?**
> **A.** 작업 중 락이 만료되면 다른 인스턴스가 같은 배치를 집어 **중복 처리**. 그래서:
> - 가장 길게 도는 환불 배치는 360초로 넉넉히
> - 모든 배치 작업이 **멱등**하게 설계됨 (이미 처리된 건은 상태 가드로 스킵)
> - 예: outbox 발행 성공 시 행 DELETE → 중복 실행해도 이미 사라진 행 → noop

---

### 🔴 Q6. `@TransactionalEventListener(AFTER_COMMIT)` 동작 원리는?

**A.** Spring 의 `ApplicationEventPublisher.publishEvent()` 는 동기 디스패치가 기본인데, `@TransactionalEventListener` 는 트랜잭션 동기화(TransactionSynchronization) 에 등록되어 트랜잭션 커밋/롤백 단계마다 호출됩니다.

| Phase | 호출 시점 |
|-------|-----------|
| BEFORE_COMMIT | 커밋 직전 |
| AFTER_COMMIT | 커밋 성공 후 (롤백 시 호출 안 됨) |
| AFTER_ROLLBACK | 롤백 후 |
| AFTER_COMPLETION | 커밋/롤백 무관하게 종료 후 |

`AFTER_COMMIT` 을 쓴 이유: Redis 홀드 해제는 **DB 예약 확정이 진짜로 영속화된 후** 에만 해야 안전. 만약 BEFORE_COMMIT 또는 트랜잭션 안에서 풀면, 후속 롤백 시 "예약 없음 + 홀드 없음" 상태가 되어 좌석이 잠시 비어 보일 수 있습니다.
