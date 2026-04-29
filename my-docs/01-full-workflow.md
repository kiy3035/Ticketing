# 01. 전체 워크플로우 (백엔드 관점)

사용자 요청 1건이 **API → 서비스 → DB/Redis/Kafka** 어떤 순서로 흘러가는지 백엔드 관점에서 정리.

---

## 1. 사용자 예매 플로우 (정상 경로)

### 1) 로그인·공연 목록

- **API**: `POST /api/auth/login` → `TokenPairResponse(accessToken, refreshToken)` 발급 (`AuthApiController` → `AuthenticationManager` → `JwtTokenIssueService`)
- **API**: `GET /api/concerts` (`ConcertController` → `ConcertService.listConcerts()`)
  - **Redis 캐시**: `@Cacheable(cacheNames=CONCERT_LIST)`, TTL 5분 (`RedisConfig`)
  - 캐시 미스 시 MySQL `concert` 테이블 조회

### 2) 대기열 필요 여부 판단 (패턴 B)

- **API**: `GET /api/queue/required?concertId=...` (`QueueController.required()`)
- **로직**: `QueueService.countWaiting()` → Redis ZSet `queue:concert:{concertId}` 크기 조회
- **분기**: 대기 인원 ≤ `ticketing.queue.activation-threshold`(기본 50) 이면 `required=false` → 대기열 페이지 스킵, 바로 좌석 페이지

### 3) 대기열 진입 (필요 시)

- **API**: `POST /api/queue/enter?concertId=...` (`QueueController.enter()`)
- **`QueueService.enterQueue()`**:
  1. 같은 사용자의 기존 토큰 제거 (사용자당 1토큰 보장)
  2. 새 UUID 토큰 발급
  3. Redis 저장 (서킷브레이커 `RedisCircuitBreakerExecutor` 통해 호출):
     - `queue:token:{token}` = `{userId, concertId, enteredAt}` JSON, TTL = `ticketing.queue.token-ttl-seconds`
     - `queue:concert:{concertId}` ZSet에 토큰 추가 (score = 진입 시각 ms)
  4. **즉시 입장**: 대기 인원 ≤ `immediate-allow-threshold`(기본 30) 이고 가용 좌석 ≥ 대기 인원이면 `queue:allowed:{token}` 즉시 SET
- **응답**: `{token, rank, totalWaiting, immediatelyAllowed}`

### 4) 대기열 폴링·입장 허용

- **API**: `GET /api/queue/status?token=...&concertId=...`
- **응답 항목**: `rank`, `totalWaiting`, `isAllowed`, `availableSeats`(잔여석 캐시 TTL 2초), `estimatedWaitMinutes`
- **입장 허용 측**: `QueueProcessingScheduler` 가 2초마다 공연별 상위 N명에게 `queue:allowed:{token}` 설정
  - 허용 수 = `min(batchSize=50, availableSeats=총-RESERVED, 상위 토큰 수)`

### 5) 좌석 조회

- **API**: `GET /api/concerts/{concertId}/seats` (`SeatController` → `SeatService.listSeats()`)
- **로직**: MySQL `seat` 조회 + `HoldStore.findHeldSeatIds(seatIds)` (`MGET hold:seat:*`) 로 홀드 좌석 파악
- **상태 오버레이**: DB `RESERVED` 그대로 / DB `AVAILABLE`이고 Redis 홀드 있으면 `HELD` / 아니면 `AVAILABLE`

### 6) 홀드 생성

- **API**: `POST /api/holds` body `{ concertId, seatId }` (`HoldController` → `HoldService.createHold()`)
- **순서**:
  1. `Seat` 조회 + 검증 (concertId 일치, 공연 CANCELLED 아님, 과거 공연 아님)
  2. `lock:seat:{seatId}` 분산 락 획득 (`RedisLockService.tryLock(ttl)`)
     - SETNX + UUID 토큰 + TTL=`ticketing.lock.ttl-seconds`(기본 5초)
     - 실패 시 `retryCount` 만큼 재시도, 그래도 실패면 429
  3. DB seat가 이미 `RESERVED`면 409
  4. **`HoldStore.createHold()`** — Redis Lua 스크립트로 원자 처리:
     - `EXISTS hold:seat:{seatId}` 체크 (이미 있으면 0 반환)
     - `SET hold:seat:{seatId} = holdToken EX ttl`
     - `SET hold:token:{holdToken} = HoldInfo JSON EX ttl`
     - `ZADD hold:expires score=만료시각ms member=payload`
  5. `SADD hold:user:{userId} holdToken` (사용자별 홀드 인덱스)
  6. **Kafka 직접 발행**: `SeatHoldEventPublisher.publish(HOLD_CREATED, info)` → `ticketing.seat-hold-events`
  7. `seatService.evictQueueStatusAvailableSeats(concertId)` — 잔여석 캐시 무효화
  8. **finally**에서 `lock:seat:{seatId}` unlock (Lua: 내 토큰일 때만 DEL)

### 7) 결제 (POINT 또는 CARD 3단계)

#### request — `POST /api/payments/request`
- **`PaymentService.requestPayment()`**:
  - 홀드 검증 (소유자, 존재)
  - **홀드 TTL 연장**: `HoldStore.extendHoldTtl(holdToken, 20분)` — seat/token 키 TTL 갱신 + ZSet 스코어 갱신
  - 동일 holdToken에 기존 Payment 있으면 그대로 반환 (재요청 안전)
  - Seat 조회, 공연 CANCELLED 검사
  - `Payment` 생성: `READY` 상태, holdToken·userId·concertId·seatId·amount·paymentMethod
  - CARD면 `orderId` 부여 (토스 위젯용)

#### approve — `POST /api/payments/{paymentKey}/approve`
- **`PaymentService.approvePaymentWithOption()`**:
  - `Payment` `PESSIMISTIC_WRITE` 조회 + 소유자 검증
  - 이미 APPROVED/COMPLETED면 그대로 반환, CANCELED면 409
  - **CARD**: body 검증 → `TossPaymentsClient.confirmPayment()` 호출 → `tossPaymentKey` 저장 → APPROVED
  - **POINT**: `Users` `PESSIMISTIC_WRITE` 조회 → 잔액 검사 → 차감 → APPROVED

#### complete — `POST /api/payments/{paymentKey}/complete`
- **`PaymentService.completePayment()`**:
  - `Payment` `PESSIMISTIC_WRITE` + 소유자 검증, APPROVED 아니면 409
  - **`ReservationService.confirm()`** 호출 (자세한 트랜잭션 경계는 `03-hold-lock-and-reservation.md` 참고)
  - 실패 시 **Saga 보상**: `PaymentCompensationService.compensateAfterReservationFailure()` (REQUIRES_NEW 트랜잭션) → 포인트 환불 + 결제 CANCELED
  - 성공 시 `Payment.status=COMPLETED`, `completedAt`, `reservationId` 저장
  - **Kafka 직접 발행**: `PaymentCompleteEventPublisher.publish()` → `ticketing.payment-complete` 토픽 (이메일/SMS 알림용)

### 8) 알림 발송 (비동기, Kafka 컨슈머 측)

- **`SeatHoldEventConsumer`** (group `ticketing-notification`):
  - `RESERVATION_CONFIRMED` 또는 `HOLD_EXPIRED` 만 처리
  - Redis List `notify:user:{userId}` 에 LPUSH + LTRIM 50건 + TTL 7일
  - `SseNotificationService` 로 SSE 푸시
- **`PaymentCompleteEventConsumer`** (group `ticketing-payment-notification`):
  - `PaymentNotificationService.notifyPaymentComplete()` → Users.notiType 따라 EmailService/SmsService 분기
- **VT 적용**: 두 컨슈머 모두 `setListenerTaskExecutor(virtualThreadExecutor)` (KafkaConfig)

### 9) 예매 내역 조회

- **API**: `GET /api/reservations/me` (`ReservationController` → `ReservationService.listByUser()`)
- **로직**: `reservation` JOIN + `payment.payment_method` 보충해서 표시

---

## 2. 판매자: 공연 등록·취소

- **공연 등록**: `POST /api/seller/concerts` → `POST /api/seller/concerts/{id}/seats`
- **공연 취소**: `POST /api/seller/concerts/{concertId}/cancel`
  - `Concert.status = CANCELLED` 만 저장
  - **환불은 즉시 하지 않음** → 별도 배치(`RefundForCancelledConcertScheduler`)가 5분 주기로 처리

---

## 3. 환불 배치 (공연 취소 후)

- **`RefundForCancelledConcertScheduler`**: `fixedDelay = ticketing.refund.interval-ms`(기본 5분)
- **분산 락**: `lock:batch:refund` (TTL 360초)
- **동작**:
  1. `ConcertRepository.findByStatus(CANCELLED)` 로 취소 공연 조회
  2. 공연별로 `Payment.status = COMPLETED` 인 건 페이징(batchSize=50)
  3. **Virtual Thread 풀**(`Executors.newVirtualThreadPerTaskExecutor()`)로 청크 내 결제 건 병렬 처리
  4. 각 건 `PaymentService.refundCompletedPaymentForCancelledConcert(paymentId)` 호출
     - 예약 CANCELLED + Seat AVAILABLE 복구
     - POINT면 환불 (실패 시 false 반환 → 다음 배치 재시도)
     - Payment CANCELED 저장

---

## 4. 한 줄 요약 시퀀스

```
[로그인]              POST /api/auth/login → TokenPair 발급
  ↓
[공연 목록]            GET /api/concerts (Redis @Cacheable)
  ↓
[대기열 필요?]         GET /api/queue/required (활성화 임계값 비교)
  ↓ 필요 시
[대기열 진입]          POST /api/queue/enter (Redis ZSet + token + allowed)
  ↓ 폴링 후 isAllowed=true
[좌석 조회]            GET /api/concerts/{id}/seats (DB + Redis 홀드 오버레이)
  ↓
[홀드]                 POST /api/holds (락 → Lua → Kafka HOLD_CREATED)
  ↓
[결제 request]         POST /api/payments/request (READY + 홀드 TTL 20분 연장)
  ↓
[결제 approve]         POST /api/payments/{key}/approve (POINT 차감 or 토스 confirm)
  ↓
[결제 complete]        POST /api/payments/{key}/complete
                       └ ReservationService.confirm()
                          ├ DB: Seat=RESERVED + Reservation INSERT + outbox INSERT (한 트랜잭션)
                          └ AFTER_COMMIT 리스너: Redis 홀드 release만
                       └ KafkaOutboxPublishScheduler가 outbox→Kafka 발행
                       └ PaymentCompleteEventPublisher (직접) → 이메일/SMS Consumer
  ↓
[예매 내역]            GET /api/reservations/me
```

공연 취소는 seller에서 `cancel` API → DB만 `CANCELLED` → 환불은 5분 주기 배치가 처리.
