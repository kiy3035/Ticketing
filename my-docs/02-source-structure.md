# 02. 소스 구조 (패키지·클래스 역할)

`src/main/java/com/inyoung/ticketing/` 기준 — **각 패키지가 무슨 일을 하는지, 핵심 클래스가 어디서 호출되는지** 정리.

---

## 1. 패키지별 요약

| 패키지 | 역할 | 핵심 클래스 |
|--------|------|-------------|
| **auth** | 회원가입, 로그인, JWT, Refresh jti DB 저장·폐기, Access Redis 블랙리스트 | `AuthApiController`, `UsersService`, `JwtTokenIssueService`, `JwtAuthenticationService`, `JwtAuthenticationFilter`, `RefreshTokenPersistenceService`, `TokenBlacklistService`, `RefreshToken` |
| **concert** | 공연 목록/상세, 카테고리·과거 공연 필터 | `ConcertController`, `ConcertService` |
| **seat** | 좌석 목록(DB + Redis 홀드 반영), 잔여석 캐시 | `SeatController`, `SeatService` |
| **queue** | 콘서트별 대기열 진입/순번/입장 허용/퇴장 | `QueueController`, `QueueService` |
| **hold** | 좌석 홀드 생성·취소·내 홀드 목록 | `HoldController`, `HoldService`, `HoldStore`, `HoldInfo`, `HoldPayload` |
| **hold.event** | 홀드/예약 Kafka 이벤트 직접 발행 + 컨슈머 | `SeatHoldEvent`, `SeatHoldEventType`, `SeatHoldEventPublisher`, `SeatHoldEventConsumer` |
| **lock** | Redis 분산 락 (좌석/배치 단위) | `LockService`(인터페이스), `RedisLockService` |
| **reservation** | 예약 확정(내부 호출 전용), 예매 내역 조회 | `ReservationController`(GET /me), `ReservationService`, `ReservationConfirmedEvent`, `ReservationConfirmedEventListener` |
| **payment** | 결제 request/approve/complete/cancel, 환불, 토스 연동, **Saga 보상** | `PaymentController`, `PaymentService`, `PaymentCompensationService`, `TossPaymentsClient`, `PaymentCompleteEventPublisher`, `PaymentCompleteEventConsumer` |
| **outbox** | 예약 확정 시 Kafka 발행 의무를 DB 적재 | `KafkaOutboxService`, `KafkaOutboxRepository`, `KafkaOutbox`, `KafkaOutboxStatus` |
| **scheduler** | 대기열·홀드·환불·**Outbox 발행** 배치 5종 | `QueueProcessingScheduler`, `QueueCleanupScheduler`, `HoldCleanupScheduler`, `RefundForCancelledConcertScheduler`, `KafkaOutboxPublishScheduler` |
| **notification** | 알림 저장(Redis), SSE 스트림, 결제 완료 시 이메일/SMS | `NotificationController`, `NotificationSseController`, `NotificationService`, `SseNotificationService`, `PaymentNotificationService`, `EmailService`, `SmsService` |
| **admin** | 통계, 유저/결제 목록, 미판매 좌석 | `AdminController`, `AdminService` |
| **seller** | 판매자용 공연·좌석 등록, 예약·매출 조회, 공연 취소 | `SellerController`, `SellerService` |
| **config** | Security, Redis, Kafka, 결제, Resilience, OpenAPI, **SSE Pub/Sub** | `SecurityConfig`, `RedisConfig`, `KafkaConfig`, `PaymentConfig`, `ResilienceConfig`, `OpenApiConfig`, `SseRedisConfig`, `TicketingProperties`, `AppConfig` |
| **common.api** | 공통 응답·예외 처리 | `ApiResponse`, `ErrorResponse`, `ApiResponseAdvice`, `GlobalExceptionHandler` |
| **common.idempotency** | `@Idempotent` AOP + Redis 키 저장 | `Idempotent`, `IdempotencyAspect`, `IdempotencyService` |
| **common.ratelimit** | `@RateLimit` AOP + Redis Sliding Window Lua | `RateLimit`, `RateLimitAspect`, `RateLimitService` |
| **common.resilience** | Redis 호출을 서킷브레이커로 감싸는 헬퍼 | `RedisCircuitBreakerExecutor` |
| **common.exception** | 공통 예외/에러 코드 | `BusinessException`, `ErrorCode` |
| **common.cache** | 캐시 키 생성 | `CacheKeyConfig` |
| **cache** | 캐시 이름 상수 | `CacheNames` |
| **health** | DB+Redis 통합 헬스, 개별 지표 | `TicketingDatastoresHealthIndicator`, `DatabaseHealthIndicator`, `RedisHealthIndicator`, `KafkaHealthIndicator` |
| **metrics** | 접속자 수, 대기열·홀드 메트릭 | `MetricsController`, `MetricsService`, `ActiveUserTracker`, `QueueMetrics`, `HoldMetrics`, `HoldReleaseMetrics`, `BusinessMetricsService` |

---

## 2. 핵심 호출 경로

### 예매 한 번에 타는 경로
```
ConcertController.list
  → SeatController.listSeats(concertId)
  → HoldController.create(POST /api/holds)
       → HoldService.createHold()
            → RedisLockService.tryLock("lock:seat:{id}")
            → HoldStore.createHold() [Lua]
            → SeatHoldEventPublisher.publish(HOLD_CREATED) [직접 Kafka send]
  → PaymentController.request/approve/complete
       → PaymentService.completePayment()
            → ReservationService.confirm()
                 ├ Seat.status = RESERVED (DB save)
                 ├ Reservation save
                 ├ ApplicationEventPublisher.publish(ReservationConfirmedEvent)
                 └ KafkaOutboxService.enqueueSeatHoldEvent(RESERVATION_CONFIRMED)  ── 모두 한 트랜잭션
            ── 트랜잭션 커밋 ──
            → ReservationConfirmedEventListener (AFTER_COMMIT)
                 └ HoldStore.releaseHold(holdToken) — Redis 홀드만 해제
            → PaymentCompleteEventPublisher.publish [직접 Kafka send]
       → KafkaOutboxPublishScheduler (별도 스케줄)
            └ outbox 행 read → kafkaTemplate.send().get(timeout) → 성공 시 행 DELETE
```

### 대기열 입장 허용 경로
```
QueueProcessingScheduler @Scheduled(2초)
  → LockService.tryLock("lock:batch:queue-process")  // 단일 인스턴스만
  → ConcertRepository.findAll()
  → 공연별로 min(batchSize, 가용좌석, 토큰수) 만큼
       QueueService.allowEntry(token, concertId)  // queue:allowed:{token} SET
```

### 홀드 만료 정리 경로
```
HoldCleanupScheduler @Scheduled(60초)
  → LockService.tryLock("lock:batch:hold-cleanup")
  → HoldStore.findExpiredHolds(now, batchSize=200)  // hold:expires ZSet rangeByScore
  → Executors.newVirtualThreadPerTaskExecutor() 로 건마다 병렬 처리
       ├ HoldStore.releaseByPayload(...)
       ├ HoldReleaseMetrics.recordReleased("timeout")
       ├ SeatHoldEventPublisher.publish(HOLD_EXPIRED) [직접 Kafka send]
       └ SeatService.evictQueueStatusAvailableSeats(concertId)
```

### 환불 배치 경로
```
RefundForCancelledConcertScheduler @Scheduled(5분)
  → LockService.tryLock("lock:batch:refund", 360s)
  → ConcertRepository.findByStatus(CANCELLED)
  → 공연별 페이징(batchSize=50) Payment.status=COMPLETED
  → Executors.newVirtualThreadPerTaskExecutor()
       └ PaymentService.refundCompletedPaymentForCancelledConcert(paymentId)
            ├ ReservationService.cancelReservationForRefund() — Reservation findWithLockById
            ├ refundPoints() — Users findWithLockByUsername (POINT만)
            └ Payment.status = CANCELED
```

---

## 3. 중요한 인터페이스/구현

- **`LockService`** (인터페이스) → 구현체 **`RedisLockService`** — 좌석 락·배치 락 모두 사용
- **`HoldStore`** — Redis 전용, 인터페이스 없음. Lua 스크립트로 원자성 보장. 사용처:
  - `HoldService` (생성·취소)
  - `ReservationService` (확정 시 검증·release는 리스너에서)
  - `PaymentService` (TTL 연장)
  - `SeatService` (좌석 목록 시 홀드 여부)
  - `QueueController`/`SeatService` (가용 좌석 수)
- **`ReservationService.confirm()`** — `PaymentService.completePayment()`에서만 호출. 컨트롤러에 예약 확정 POST 없음.
- **`RedisCircuitBreakerExecutor`** — `HoldStore`, `QueueService` 등이 Redis 호출을 이걸로 감싸 fallback 제공.

---

## 4. 설정·프로퍼티

- **`TicketingProperties`** (`@ConfigurationProperties(prefix="ticketing")`) — 다음 그룹으로 묶임:
  - `Hold` (ttlSeconds, cleanupIntervalMs, cleanupBatchSize)
  - `Lock` (ttlSeconds, retryCount, retryDelayMs)
  - `Kafka` (holdTopic)
  - `Queue` (batchSize, processingIntervalMs, tokenTtlSeconds, cleanupIntervalMs, immediateAllowThreshold, activationThreshold)
  - `Payment` (holdExtensionTtlSeconds)
  - `Refund` (batchSize, intervalMs)
  - `Toss` (clientKey, secretKey, securityKey)
  - `RateLimitProps` (enabled, requestsPerSecond, windowSeconds)
  - `Outbox` (publishIntervalMs, batchSize, maxPublishAttempts, publishTimeoutSeconds)
  - `Jwt` (secret, accessTtlMinutes, refreshTtlDays, accessTtlSeconds, refreshTtlSeconds)
- **`application.properties`**: 위 키들을 `ticketing.*` 로 외부화. JWT 시크릿·DB·Redis·Kafka·Toss·SMTP·SMS는 `.env` 변수 주입.

---

## 5. ArchUnit 규칙

`src/test/java/.../architecture/ArchitectureTest.java` 가 다음을 강제:
- `Controller` 는 `Repository` 를 직접 주입받지 않는다 (반드시 Service 경유).
- 따라서 `QueueController` 같은 곳도 `SeatService` 의 `countAvailableSeatsForQueueStatus(concertId)` 같은 메서드를 통해 좌석 수를 본다.
