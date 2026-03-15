# 05. 스케줄러 4종 (상세)

백그라운드에서 주기적으로 도는 4개 스케줄러가 **언제**, **무엇을**, **어떤 설정**으로 하는지 정리했다.

---

## 1. QueueProcessingScheduler — 대기열 입장 허용

- **역할**: 콘서트별 대기열에서 **상위 N명**에게 "입장 허용" 상태를 준다. 입장 허용된 사용자는 좌석 페이지로 들어갈 수 있다.
- **주기**: fixedDelay, `ticketing.queue.processing-interval-ms` (기본 2000 = 2초)
- **동작**:
  - 분산 락 `lock:batch:queue-process` 획득 (한 인스턴스만 실행)
  - TicketingProperties.getQueue().getBatchSize() (설정: ticketing.queue.batch-size, 기본 50)로 상위 N명 결정
  - ConcertRepository.findAll()로 모든 공연 순회, 공연별 예매 가능 좌석 수(min(배치크기, 가용좌석))만큼 상위 토큰에 QueueService.allowEntry(token, concertId) 호출
  - allowEntry는 Redis `queue:allowed:{token}` 에 입장 허용 정보 저장 (TTL 있음)
- **프론트**: 대기열 페이지에서 2초마다 status 호출해 isAllowed true면 concert 페이지로 이동

---

## 2. QueueCleanupScheduler — 대기열 만료 토큰 정리

- **역할**: ZSet에는 있는데 `queue:token:{token}` 키가 이미 만료돼 없어진 "유령 토큰"을 ZSet에서 제거한다.
- **주기**: fixedDelay, `ticketing.queue.cleanup-interval-ms` (기본 60000 = 60초)
- **동작**: 분산 락 `lock:batch:queue-cleanup` 획득 후, 콘서트별 queue ZSet 스캔, token 키 존재 여부 확인 후 없으면 ZREM

---

## 3. HoldCleanupScheduler — 홀드 만료 정리

- **역할**: 만료된 홀드를 Redis에서 제거하고, Kafka로 HOLD_EXPIRED 이벤트를 보낸다 (알림·통계 등).
- **주기**: fixedDelay, `ticketing.hold.cleanup-interval-ms` (기본 60000 = 60초)
- **동작**:
  - 분산 락 `lock:batch:hold-cleanup` 획득
  - HoldStore.findExpiredHolds(now, batchSize) — ZSet `hold:expires`에서 스코어 ≤ now 인 항목 조회
  - 각 항목에 대해 holdStore.releaseByPayload(), SeatHoldEventPublisher.publish(HOLD_EXPIRED, info)

---

## 4. RefundForCancelledConcertScheduler — 취소된 공연 환불

- **역할**: 공연이 CANCELLED로 바뀐 경우, 그 공연의 **COMPLETED** 결제를 건별로 예약 취소·포인트 환불·결제 CANCELED 처리한다.
- **주기**: fixedDelay, `ticketing.refund.interval-ms` (기본 300000 = 5분)
- **동작**:
  - 분산 락 `lock:batch:refund` 획득
  - ConcertRepository.findByStatus(CANCELLED)
  - 공연별로 PaymentRepository.findByConcertIdAndStatus(concertId, COMPLETED, PageRequest) 로 청크 조회 (batchSize, 기본 50)
  - 각 Payment에 대해 PaymentService.refundCompletedPaymentForCancelledConcert(paymentId) 호출 (순서: 예약 취소 → 포인트 환불 → 결제 CANCELED)
- **테스트**: 공연 취소 후 한 주기 기다리거나, `ticketing.refund.interval-ms=10000` 등으로 줄여서 확인.

---

## 5. 설정 요약

| 설정 | 기본값 | 설명 |
|------|--------|------|
| ticketing.queue.processing-interval-ms | 2000 | 대기열 입장 허용 주기 |
| ticketing.queue.cleanup-interval-ms | 60000 | 대기열 만료 토큰 정리 주기 |
| ticketing.hold.cleanup-interval-ms | 60000 | 홀드 만료 정리 주기 |
| ticketing.refund.interval-ms | 300000 | 취소 공연 환불 배치 주기 |
| ticketing.refund.batch-size | 50 | 환불 배치 청크 크기 |
| ticketing.hold.cleanup-batch-size | (properties 참고) | 홀드 만료 스캔 배치 크기 |

주기·배치 크기 선택 근거는 `docs/infra.md` 참고.
