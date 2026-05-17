# 05. 스케줄러 5종 (상세)

`scheduler` 패키지의 **5개 스케줄러**가 **언제·무엇을·어떤 락·설정**으로 도는지.

전부 **`LockService.tryLock("lock:batch:…")`** 으로 다중 인스턴스 시 **한 노드만** 실행한다.

---

## 1. `QueueProcessingScheduler` — 대기열 입장 허용

- **역할**: 콘서트별 대기열 ZSet 상위 N명에게 입장 허용 부여 → 좌석 페이지 진입 가능.
- **주기**: `fixedDelay = ${ticketing.queue.processing-interval-ms:2000}` (기본 2초)
- **분산 락**: `lock:batch:queue-process` TTL 15초
- **동작**:
  1. `ConcertRepository.findAll()` 로 모든 공연 순회
  2. 공연별로:
     - `totalSeats = seatRepository.countByConcertId()`
     - `reservedCount = countByConcertIdAndStatus(RESERVED)`
     - `availableSeats = totalSeats - reservedCount`
     - `allowCount = totalSeats == 0 ? batchSize : min(batchSize, availableSeats)`
  3. `queueService.getTopTokens(concertId, batchSize)` — ZSet 상위 N개
  4. 토큰별로 이미 허용 안됐는지(`isAllowed`), 토큰 데이터 존재(`getTokenData`), concertId 일치 확인 후 `queueService.allowEntry(token, concertId)` (= `SET queue:allowed:{token}` TTL=tokenTtl)
- **메트릭**: `ticketing_batch_run_duration_seconds{batch=queue-process}`, `ticketing_batch_run_total{batch=queue-process,status=success|failure}`

**개선 여지**: 현재 `findAll()` 로 전 공연을 순회 — 공연 수가 많아지면 "대기열 있는 공연만" 추리는 인덱싱 필요.

---

## 2. `QueueCleanupScheduler` — 대기열 만료 토큰 정리

- **역할**: ZSet에는 남아 있지만 `queue:token:{token}` String이 TTL로 만료된 **유령 토큰**을 ZSet에서 제거.
- **주기**: `fixedDelay = ${ticketing.queue.cleanup-interval-ms:60000}` (60초)
- **분산 락**: `lock:batch:queue-cleanup`
- **동작**: 콘서트별 ZSet `ZSCAN` → 토큰 String 키 존재 여부 확인 → 없으면 `ZREM` 모음 후 일괄 삭제

---

## 3. `HoldCleanupScheduler` — 홀드 만료 정리

- **역할**: 만료된 홀드를 Redis에서 제거하고, Kafka로 `HOLD_EXPIRED` 직접 발행.
- **주기**: `fixedDelay = ${ticketing.hold.cleanup-interval-ms:60000}` (60초)
- **분산 락**: `lock:batch:hold-cleanup` TTL 90초
- **동작**:
  1. `holdStore.findExpiredHolds(now, batchSize=200)` — `hold:expires` ZSet에서 score ≤ now 인 항목 조회
  2. **Virtual Thread 풀**(`Executors.newVirtualThreadPerTaskExecutor`)로 건마다 병렬:
     - `holdStore.releaseByPayload(info, payload)` — Lua release script로 seat·token·zset·user set 정리
     - `HoldReleaseMetrics.recordReleased("timeout")`
     - `eventPublisher.publish(HOLD_EXPIRED, info)` — Kafka 직접 send
     - `seatService.evictQueueStatusAvailableSeats(concertId)` — 잔여석 캐시 무효화
  3. try-with-resources로 Executor close → 모든 태스크 완료 대기

**왜 VT인가?** 건당 Redis I/O + Kafka I/O. 200건 순차 처리하면 ~1.6초, 병렬이면 ~50ms.

---

## 4. `RefundForCancelledConcertScheduler` — 취소된 공연 환불

- **역할**: `Concert.status = CANCELLED` 인 공연의 **COMPLETED** 결제를 청크로 조회 → 건마다 예약 취소·포인트 환불·결제 CANCELED.
- **주기**: `fixedDelay = ${ticketing.refund.interval-ms:300000}` (5분)
- **분산 락**: `lock:batch:refund` TTL **360초** (배치 길어도 락 유지)
- **동작**:
  1. `concertRepository.findByStatus(CANCELLED)`
  2. 공연별 `paymentRepository.findByConcertIdAndStatus(..., COMPLETED, PageRequest)` 페이징 (batchSize 50)
  3. 청크 내 결제 건을 **Virtual Thread 풀**로 병렬 처리
     - `paymentService.refundCompletedPaymentForCancelledConcert(payment.getId())` 호출
     - `AtomicInteger`로 `totalRefunded`, `totalFailed` 카운트 (VT 안전)
- **메트릭**: `ticketing_refund_processed_total` Counter
- **테스트**: `ticketing.refund.interval-ms=10000` 으로 단축 후 재기동

---

## 5. `KafkaOutboxPublishScheduler` — Outbox → Kafka 발행

- **역할**: `kafka_outbox` 테이블에서 `status=PENDING` 행을 읽어 `ticketing.seat-hold-events` 로 `SeatHoldEvent` 전송. **현재 outbox를 쓰는 건 `RESERVATION_CONFIRMED` 뿐.**
- **주기**: `fixedDelay = ${ticketing.outbox.publish-interval-ms:500}` (500ms)
- **분산 락**: `lock:batch:kafka-outbox` TTL 120초
- **동작**:
  1. `transactionTemplate.executeWithoutResult(...)` — `@Scheduled` 메서드는 프록시 밖이라 `@Transactional` 자기호출이 안 먹히는 함정 회피
  2. `repository.findByStatusOrderByIdAsc(PENDING, PageRequest.of(0, batchSize))` (batchSize=50, 오래된 순)
  3. 행마다:
     - JSON → `SeatHoldEvent` 역직렬화
     - `kafkaTemplate.send(topic, partitionKey=seatId, event).get(15s)` — **send 완료 대기**
     - 성공 시 `repository.delete(row)` (SENT 상태 컬럼 없이 그냥 삭제)
     - 실패 시 `handlePublishFailure(row, ...)`:
       - `publishAttempts++`, `lastError` 저장
       - `>= maxPublishAttempts(25)` 면 `status = FAILED` 로 전환 (자동 재시도 중단, 운영 개입 대상)
- **메트릭**:
  - `ticketing_batch_run_duration_seconds{batch=kafka-outbox}` Timer
  - `ticketing_outbox_published_total` Counter (성공)
  - `ticketing_outbox_publish_failures_total` Counter (재시도 또는 dead)

**다른 홀드 이벤트(`HOLD_CREATED`, `HOLD_CANCELED`, `HOLD_EXPIRED`)와의 차이**: 이들은 `SeatHoldEventPublisher`가 **Kafka에 직접 send** — outbox 미경유. "DB 커밋과 반드시 같이 가야 하는 발행"인 `RESERVATION_CONFIRMED` 만 outbox 사용.

---

## 6. 설정 요약표

| 설정 키 | 기본값 | 코드 위치 | 설명 |
|---------|--------|-----------|------|
| `ticketing.queue.processing-interval-ms` | 2000 | `QueueProcessingScheduler` | 대기열 입장 허용 주기 |
| `ticketing.queue.batch-size` | 50 | `QueueProcessingScheduler` | 한 번에 허용할 최대 인원 |
| `ticketing.queue.cleanup-interval-ms` | 60000 | `QueueCleanupScheduler` | 유령 토큰 정리 주기 |
| `ticketing.queue.cleanup-batch-size` | 200 | `QueueCleanupScheduler` | ZSCAN 한 번에 보는 수 |
| `ticketing.queue.token-ttl-seconds` | 60 | `QueueService` | 대기열 토큰 String TTL (실서비스 1800 권장) |
| `ticketing.queue.activation-threshold` | 50 | `QueueController.required` | 대기열 화면 활성화 임계치 |
| `ticketing.queue.immediate-allow-threshold` | 30 | `QueueController.enter` | 즉시 입장 허용 임계치 |
| `ticketing.hold.ttl-seconds` | 300 | `HoldStore.createHold` | 좌석 홀드 TTL (5분, 결제 진입 시 1200으로 연장) |
| `ticketing.hold.cleanup-interval-ms` | 60000 | `HoldCleanupScheduler` | 만료 홀드 정리 주기 |
| `ticketing.hold.cleanup-batch-size` | 200 | `HoldCleanupScheduler` | 한 번에 처리할 만료 홀드 |
| `ticketing.lock.ttl-seconds` | 3 | `RedisLockService` | 좌석 락 TTL (정상 흐름이 1초 내 종료되는 것 기준 3배 여유) |
| `ticketing.lock.retry-count` | 0 | `HoldService.createHold` | 락 재시도 횟수 |
| `ticketing.payment.hold-extension-ttl-seconds` | 1200 | `PaymentService.requestPayment` | 결제 진행 중 홀드 연장 TTL (20분) |
| `ticketing.refund.interval-ms` | 300000 | `RefundForCancelledConcertScheduler` | 환불 배치 주기 (5분) |
| `ticketing.refund.batch-size` | 50 | `RefundForCancelledConcertScheduler` | 한 페이지 결제 건 수 |
| `ticketing.outbox.publish-interval-ms` | 500 | `KafkaOutboxPublishScheduler` | Outbox 발행 주기 |
| `ticketing.outbox.batch-size` | 50 | `KafkaOutboxPublishScheduler` | 한 번에 읽는 outbox 행 |
| `ticketing.outbox.max-publish-attempts` | 25 | `KafkaOutboxPublishScheduler` | FAILED 전환 임계치 |
| `ticketing.outbox.publish-timeout-seconds` | 15 | `KafkaOutboxPublishScheduler` | `kafkaTemplate.send().get()` 대기 |

---

## 7. 분산 락 키 모음

| 락 키 | TTL | 사용처 |
|-------|-----|--------|
| `lock:batch:queue-process` | 15s | `QueueProcessingScheduler` |
| `lock:batch:queue-cleanup` | (코드 default) | `QueueCleanupScheduler` |
| `lock:batch:hold-cleanup` | 90s | `HoldCleanupScheduler` |
| `lock:batch:refund` | 360s | `RefundForCancelledConcertScheduler` |
| `lock:batch:kafka-outbox` | 120s | `KafkaOutboxPublishScheduler` |

배치 락 TTL이 배치 실행 시간보다 짧으면 두 인스턴스가 동시에 들어올 수 있다 → 가장 길게 도는 환불 배치는 360초로 넉넉히.
