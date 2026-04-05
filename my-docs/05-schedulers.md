# 05. 스케줄러 5종 (상세)

백그라운드에서 주기적으로 도는 **5개** 스케줄러가 **언제**, **무엇을**, **어떤 락·설정**으로 하는지 정리했다.  
전부 **`LockService.tryLock("lock:batch:…")`** 으로 멀티 인스턴스일 때 **한 노드만** 배치를 실행한다.

---

## 1. QueueProcessingScheduler — 대기열 입장 허용

- **역할**: 콘서트별 대기열에서 **상위 N명**에게 "입장 허용" 상태를 준다. 입장 허용된 사용자는 좌석 페이지로 들어갈 수 있다.
- **주기**: `fixedDelay`, `ticketing.queue.processing-interval-ms` (기본 2000ms)
- **락**: `lock:batch:queue-process`
- **동작**:
  - `ConcertRepository.findAll()` 로 공연 순회 (개선 여지: 대기 중인 공연만 골라 순회)
  - 공연별 가용 좌석 수와 `ticketing.queue.batch-size`(기본 50) 중 작은 값만큼 상위 토큰에 `QueueService.allowEntry(token, concertId)` 호출
  - `allowEntry` 는 Redis `queue:allowed:{token}` 에 입장 허용 정보 저장 (TTL = 토큰 TTL과 맞춤)
- **프론트**: 대기열 페이지에서 약 2초마다 `status` 폴링 → `isAllowed` 가 true 이면 콘서트 페이지로 이동

---

## 2. QueueCleanupScheduler — 대기열 만료 토큰 정리

- **역할**: ZSet 에는 남아 있는데 `queue:token:{token}` String 이 TTL 로 사라진 **유령 토큰**을 ZSet 에서 제거한다.
- **주기**: `ticketing.queue.cleanup-interval-ms` (기본 60000ms)
- **락**: `lock:batch:queue-cleanup`
- **동작**: 콘서트별로 ZSet 을 스캔하며 토큰 키 존재 여부 확인 후 `ZREM`

---

## 3. HoldCleanupScheduler — 홀드 만료 정리

- **역할**: 만료된 홀드를 Redis 에서 제거하고, Kafka 로 `HOLD_EXPIRED` 를 **직접** 발행한다(`SeatHoldEventPublisher`).
- **주기**: `ticketing.hold.cleanup-interval-ms` (기본 60000ms)
- **락**: `lock:batch:hold-cleanup`
- **동작**:
  - `HoldStore.findExpiredHolds(now, batchSize)` — ZSet `hold:expires` 에서 score ≤ now 인 항목 조회
  - **Virtual Thread 풀**(`Executors.newVirtualThreadPerTaskExecutor`)로 건마다 `releaseByPayload` + 메트릭 + Kafka 발행을 병렬 처리 (I/O 대기가 많아 VT 가 유리)
- **배치 크기**: `ticketing.hold.cleanup-batch-size`

---

## 4. RefundForCancelledConcertScheduler — 취소된 공연 환불

- **역할**: `Concert.status = CANCELLED` 인 공연의 **COMPLETED** 결제를 청크로 조회해, 건마다 예약 취소·포인트 환불·결제 CANCELED 처리.
- **주기**: `ticketing.refund.interval-ms` (기본 300000ms = 5분)
- **락**: `lock:batch:refund`
- **동작**:
  - `ConcertRepository.findByStatus(CANCELLED)`
  - 공연별 `PaymentRepository.findByConcertIdAndStatus(..., COMPLETED, PageRequest)` 청크 (batchSize 기본 50)
  - 청크 내 각 Payment 에 대해 `Executors.newVirtualThreadPerTaskExecutor()` 로 **`refundCompletedPaymentForCancelledConcert`** 를 병렬 제출 — 트랜잭션은 건마다 독립이며 동시성은 HikariCP 가 조절
- **테스트**: `ticketing.refund.interval-ms=10000` 등으로 줄여 확인

---

## 5. KafkaOutboxPublishScheduler — Outbox → Kafka 발행

- **역할**: `kafka_outbox` 테이블에서 `status=PENDING` 인 행을 읽어 **`ticketing.seat-hold-events`** 로 `SeatHoldEvent` 를 전송한다. 현재 outbox 를 쓰는 건 **`RESERVATION_CONFIRMED`** 뿐이다.
- **주기**: `fixedDelay` = `ticketing.outbox.publish-interval-ms` (기본 500ms)
- **락**: `lock:batch:kafka-outbox` (TTL 120초)
- **동작**:
  - `TransactionTemplate.executeWithoutResult` 안에서 배치 처리(스케줄 메서드의 `@Transactional` 함정 회피)
  - `send(...).get(15, TimeUnit.SECONDS)` 로 완료 대기 후 **성공 시 행 DELETE**
  - 실패 시 `publishAttempts` 증가, `last_error` 저장; `ticketing.outbox.max-publish-attempts`(기본 25) 초과 시 `status=FAILED` 로 남김
- **설정**: `ticketing.outbox.batch-size`(기본 50)

**다른 홀드 이벤트(`HOLD_CREATED` 등)와의 차이**: 이들은 **`SeatHoldEventPublisher` 가 Kafka 에 직접 send** 하며 outbox 를 타지 않는다. "DB 커밋과 반드시 같이 가야 하는 발행"에만 outbox 를 썼다고 보면 된다.

---

## 6. 설정 요약표

| 설정 키 | 기본값(예) | 설명 |
|---------|------------|------|
| ticketing.queue.processing-interval-ms | 2000 | 대기열 입장 허용 주기 |
| ticketing.queue.cleanup-interval-ms | 60000 | 대기열 유령 토큰 정리 주기 |
| ticketing.hold.cleanup-interval-ms | 60000 | 홀드 만료 정리 주기 |
| ticketing.hold.cleanup-batch-size | 200 | 홀드 만료 스캔 배치 |
| ticketing.refund.interval-ms | 300000 | 취소 공연 환불 배치 주기 |
| ticketing.refund.batch-size | 50 | 환불 청크 크기 |
| ticketing.outbox.publish-interval-ms | 500 | Outbox 발행 스케줄 간격 |
| ticketing.outbox.batch-size | 50 | Outbox 한 번에 읽는 행 수 |
| ticketing.outbox.max-publish-attempts | 25 | 발행 실패 시 최대 재시도 횟수 |

주기·배치 크기 튜닝의 배경은 `docs/infra.md`, Redis·Kafka 키는 `my-docs/06-redis-kafka-reference.md` 참고.
