# 트랜잭션 / 일관성

---

### Q1. 이 시스템에서 데이터 일관성을 가장 신경 쓴 플로우는 어디인가요?

**A.** `PaymentService.completePayment()` → `ReservationService.confirm()` → `AFTER_COMMIT` 리스너 흐름입니다. 좌석 상태(RESERVED), 예약 레코드, 결제 상태(COMPLETED), Redis 홀드, Kafka 이벤트가 모두 일관되어야 하는데, 이 중 좌석/예약/결제는 하나의 DB 트랜잭션 안에서 처리하고, Redis 홀드 해제와 Kafka 이벤트 발행은 `ReservationConfirmedEventListener`에서 `@TransactionalEventListener(phase = AFTER_COMMIT)`으로 DB 커밋 성공 후에만 수행합니다. 이렇게 하면 트랜잭션이 롤백돼도 Redis/Kafka 상태가 DB와 어긋나지 않습니다.

> **Q1-1. AFTER_COMMIT에서 Redis/Kafka 호출이 실패하면 어떻게 되나요?**
> **A.** 홀드가 Redis에 남아있게 되지만, `HoldCleanupScheduler`가 60초마다 `hold:expires` ZSet을 스캔해 만료된 홀드를 `releaseByPayload()`로 정리합니다. DB에는 이미 좌석이 RESERVED로 확정됐으므로, 홀드가 잠시 남아있어도 다른 사용자가 같은 좌석을 홀드할 수 없습니다(`HoldStore.CREATE_SCRIPT`의 `EXISTS` 검증). 즉, 최악의 경우 불필요한 홀드가 TTL(최대 20분)까지 남지만 기능 정합성은 깨지지 않습니다.

---

### Q2. 왜 예약 확정을 별도 API가 아니라 결제 완료 API 안에서 처리했나요?

**A.** 결제와 예약을 별도 API로 나누면, 결제는 완료됐지만 예약 API 호출 전에 네트워크가 끊기는 경우 "돈은 빠졌는데 예약이 안 된" 상태가 생깁니다. `completePayment()` 안에서 `reservationService.confirm()`을 호출하면, 예약 생성에 실패하면 결제도 함께 롤백됩니다. 성공하면 항상 `좌석 RESERVED + Reservation CONFIRMED + Payment COMPLETED`가 동시에 커밋되어 불일치를 원천 차단합니다.

> **Q2-1. 그럼 "결제는 됐는데 응답이 끊긴" 상황은요?**
> **A.** Payment에 `paymentKey`, `holdToken` 고유 키와 상태 필드(READY/APPROVED/COMPLETED/CANCELED)가 있어, 운영자가 `paymentKey` 기준으로 상태를 조회할 수 있습니다. 또한 `completePayment()`은 이미 COMPLETED인 Payment에 대해 중복 호출 시 기존 결과를 반환하므로(멱등), 클라이언트가 재시도해도 안전합니다.

---

### Q3. Redis와 DB가 동시에 관여할 때 분산 트랜잭션은 어떻게 바라보셨나요?

**A.** "DB + Redis + Kafka를 하나의 분산 트랜잭션으로 묶는 건 비용 대비 실효가 낮다"고 판단하고, **DB를 source of truth로 한 최종 일관성(Eventual Consistency)** 전략을 택했습니다. 원칙은 "돈/좌석의 확정 상태는 MySQL이 진리이고, Redis/Kafka는 실시간 캐시·알림·비동기 프로세스"입니다. 그래서 모든 핵심 플로우에서 DB 트랜잭션 커밋 후에야 Redis/Kafka를 갱신하고, Redis/Kafka에서 문제가 생기면 알림만 지연될 뿐 결제/예약 정합성은 DB가 보장합니다.

> **Q3-1. 이 구조에서 Redis 데이터가 DB와 불일치하는 경우를 어떻게 탐지하나요?**
> **A.** 현재는 별도의 일관성 체크 배치가 없지만, `SeatService.listSeats()`에서 DB의 좌석 상태(RESERVED)와 Redis 홀드 상태를 매번 조합하기 때문에, 사용자에게 보이는 좌석 현황은 항상 두 저장소의 최신 상태를 반영합니다. 장기적으로는 DB의 RESERVED 좌석 수와 Redis 활성 홀드 수를 비교하는 모니터링 메트릭을 추가하면 불일치를 조기에 감지할 수 있습니다.

---

### Q4. 취소된 공연 환불 배치의 트랜잭션 처리를 설명해 주세요.

**A.** `RefundForCancelledConcertScheduler`는 5분마다 `concertRepository.findByStatus(CANCELLED)`로 취소된 공연을 조회하고, 각 공연의 COMPLETED 결제를 `PageRequest`로 청크 조회합니다. 각 결제 건은 `PaymentService.refundCompletedPaymentForCancelledConcert(paymentId)`에서 **건별 트랜잭션**으로 처리합니다. 순서는: (1) `paymentRepository.findWithLockById()` → (2) `reservationService.cancelReservationForRefund()`(Reservation CANCELLED + Seat AVAILABLE) → (3) POINT 결제면 `refundPoints()` → (4) Payment CANCELED. 예약 취소를 먼저 하는 이유는, 포인트 환불이 실패해도 좌석은 이미 해제되어 다른 사용자가 예매할 수 있도록 하기 위해서입니다.

> **Q4-1. 환불 중 특정 건이 실패하면 다른 건에 영향을 주나요?**
> **A.** 아닙니다. 각 건이 별도 트랜잭션이고, `try/catch`로 감싸서 실패 시 로그만 남기고 다음 건을 계속 처리합니다. 이미 CANCELED 상태인 결제는 `refundCompletedPaymentForCancelledConcert()`에서 `return true`로 스킵하므로 멱등합니다. 다음 배치 주기에서 실패한 건만 다시 시도됩니다.

---

### Q5. "강한 일관성"과 "최종 일관성"을 어디서 나눴는지 정리해 주세요.

**A.** 사용자가 금전적으로 체감하는 영역(결제 금액, 좌석 예약, 포인트 잔액)은 MySQL 트랜잭션 + Redis 분산 락으로 **강한 일관성**을 보장합니다. `PaymentService`의 `approve`에서 `findWithLockByUsername()`으로 포인트를 비관적 잠금 조회하고, `ReservationService.confirm()`에서 좌석 락 + DB 트랜잭션으로 RESERVED를 확정합니다. 반면 알림(SSE/이메일/SMS), 활성 사용자 수(`ActiveUserTracker`), 대기열 순번 표시, 콘서트 목록 캐시(5분 TTL) 같은 영역은 잠깐 틀리거나 늦게 반영돼도 사용 경험에 치명적이지 않으므로 **최종 일관성**을 허용합니다.

> **Q5-1. 이 경계를 어떤 기준으로 결정하셨나요?**
> **A.** "이 데이터가 1초라도 틀리면 사용자에게 금전적 손해나 좌석 중복 예약이 발생하는가?"가 기준입니다. 포인트 차감·좌석 예약·결제 상태는 1건이라도 틀리면 치명적이므로 강한 일관성, 나머지는 "최대한 빠르게 맞추되 잠깐 틀려도 괜찮다"로 분류했습니다.
