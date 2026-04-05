# 트랜잭션 / 일관성

---

### Q1. 예약 확정(`confirm`) 시 DB 와 Redis·Kafka 는 어떤 순서로 움직이나요?

**A.** 한 트랜잭션 안에서: 좌석 `RESERVED`·예약 INSERT·**`kafka_outbox` 에 `RESERVATION_CONFIRMED` 페이로드 INSERT** 까지 묶습니다. 커밋 후 `ReservationConfirmedEvent` 리스너(`AFTER_COMMIT`)가 **`holdStore.releaseHold()`** 만 호출해 Redis 홀드를 정리합니다. Kafka 로의 실제 발행은 **`KafkaOutboxPublishScheduler`** 가 outbox 행을 읽어 `send` 하고, 성공 시 `SENT` 로 갱신합니다. 순서·실패 시 “무엇이 깨지는지” 표는 [docs/sequence-diagrams §5](../docs/sequence-diagrams.md#consistency-failure-scenarios) 와 맞춥니다.

> **Q1-1. 왜 Kafka 를 트랜잭션 안에서 직접 send 안 하나요?**
> **A.** 브로커 지연이 길어지면 DB 커밋이 지연되고, send 직후 롤백이면 “메시지는 나갔는데 DB 는 없음” 같은 **이중 쓰기** 문제가 생깁니다. outbox 는 **로컬 트랜잭션 한 번**으로 “발행할 의무”를 남기고, 비동기 워커가 밀어 넣습니다.

> **Q1-2. outbox 발행이 계속 실패하면?**
> **A.** 재시도 횟수를 넘기면 `FAILED` 로 두고, 운영에서 재처리·수동 점검 대상이 됩니다. 예약·좌석 DB 상태는 이미 확정이라 **강한 일관성(예약 데이터)** 은 유지되고, **다운스트림(예: 재고 연동)** 만 지연될 수 있습니다.

---

### Q2. 결제 완료 플로우의 트랜잭션 경계는?

**A.** `PaymentService.completePayment()` 가 `@Transactional` 로 **결제 상태·예약 상태·좌석 SOLD** 를 한 트랜잭션에서 갱신합니다. 이후 `PaymentCompleteEventPublisher` 가 Kafka `PaymentComplete` 를 **직접 send** 합니다(이 경로는 outbox 가 아님). 알림 컨슈머는 **멱등**(`notification_sent` 플래그)으로 중복 처리를 막습니다.

> **Q2-1. 멱등은 어디까지 적용했나요?**
> **A.** **HTTP 결제 API** 는 `Idempotency-Key` 헤더를 받아 **동일 키·동일 바디**면 캐시된 응답을 돌려 **중복 결제 POST** 를 방지합니다. Kafka 쪽은 컨슈머·스케줄러가 DB 플래그·상태로 중복을 흡수합니다.

---

### Q3. “보상 트랜잭션”이나 환불은 어떻게 하나요?

**A.** 결제 **취소 API** 는 결제·예약·좌석을 되돌리는 **동기 보상**입니다. **콘서트 취소**는 이미 팔린 티켓에 대해 `RefundForCancelledConcertScheduler` 가 **분산 락 + 배치**로 환불·상태를 맞춥니다. Saga 오케스트레이터까지는 두지 않았고, **도메인별 스케줄 보상**으로 단순화했습니다.

> **Q3-1. 스케줄러가 두 번 돌면 중복 환불되나요?**
> **A.** `lock:batch:refund` 로 한 인스턴스만 실행하고, 처리 단위마다 상태 전이로 **이미 환불 처리된 건은 스킵**합니다.

---

### Q4. 강한 일관성 vs 최종 일관성을 이 프로젝트에서 어떻게 나눴나요?

**A.** **강한 일관성:** 예약·결제·좌석 상태는 **단일 DB 트랜잭션**과 비관적 락·유니크 제약으로 보호합니다. **최종 일관성:** 알림·일부 이벤트·outbox 발행은 **비동기**이며, 실패 시 재시도·DLT·`FAILED` 표기로 수렴시킵니다. “예약은 됐는데 이메일이 안 갔다”는 **허용 가능한 지연**으로 두고, “같은 좌석 두 번 팔림”은 **허용 불가**로 설계했습니다.

> **Q4-1. Redis 홀드와 DB 좌석이 어긋나면?**
> **A.** **이중 방어**입니다. Redis TTL 로 휘발성 선점을 풀고, 예약 확정 시 DB 에서 `AVAILABLE` 인 좌석만 `RESERVED` 로 바꿉니다. Lua 로 홀드 생성 시 좌석 ID·콘서트 ID 를 함께 검증해 **다른 공연 좌석으로 홀드 치환**을 막습니다.

---

### Q5. 분산 환경에서 “한 번만 실행”이 필요한 작업은?

**A.** `QueueProcessingScheduler`, `QueueCleanupScheduler`, `HoldCleanupScheduler`, `RefundForCancelledConcertScheduler`, **`KafkaOutboxPublishScheduler`** 가 각각 `lock:batch:queue-process`, `lock:batch:queue-cleanup`, `lock:batch:hold-cleanup`, `lock:batch:refund`, **`lock:batch:kafka-outbox`** 로 **`RedisLockService.tryLock`** 을 잡아 **멀티 인스턴스에서 단일 실행**을 보장합니다. 락 TTL 은 작업 시간보다 길게 두어 **좀비 워커**에 대비합니다.

> **Q5-1. 락 TTL 이 짧으면?**
> **A.** 작업 중 락이 만료되면 다른 인스턴스가 같은 배치를 집어 **중복 처리**될 수 있습니다. 그래서 스케줄 작업은 **멱등**(상태 전이·플래그)을 함께 두는 것이 안전합니다.
