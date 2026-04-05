# 핵심 플로우 시퀀스 다이어그램

## 1. 좌석 홀드 (선점) 플로우

```mermaid
sequenceDiagram
    participant C as Client
    participant API as HoldController
    participant HS as HoldService
    participant Lock as RedisLockService
    participant Store as HoldStore (Redis)
    participant DB as SeatRepository
    participant K as Kafka

    C->>API: POST /api/holds {concertId, seatId}
    API->>HS: createHold(request, userId)
    HS->>DB: findById(seatId) → 좌석 검증
    HS->>Lock: tryLock("lock:seat:{seatId}", TTL)
    alt 락 획득 성공
        Lock-->>HS: Optional.of(token)
        HS->>DB: seat.status == RESERVED? → 검증
        HS->>Store: createHold (Lua 스크립트)
        Note over Store: SETNX seat→token, SET token→info, ZADD expires
        alt 홀드 성공
            Store-->>HS: true
            HS->>K: publish(HOLD_CREATED, info)
            HS->>Lock: unlock(key, token)
            HS-->>API: HoldResponse(holdToken, expiresAt)
            API-->>C: 201 Created
        else 좌석 이미 홀드됨
            Store-->>HS: false
            HS->>Lock: unlock(key, token)
            HS-->>API: 409 Conflict
        end
    else 락 획득 실패
        Lock-->>HS: Optional.empty()
        HS-->>API: 429 Too Many Requests
    end
```

## 2. 결제 + 예약 확정 플로우

```mermaid
sequenceDiagram
    participant C as Client
    participant PC as PaymentController
    participant PS as PaymentService
    participant RS as ReservationService
    participant DB as Database
    participant Redis as Redis
    participant L as AfterCommitListener
    participant Sched as OutboxScheduler
    participant K as Kafka

    Note over C,K: Step 1: 결제 요청
    C->>PC: POST /api/payments/request {holdToken, paymentMethod}
    PC->>PS: requestPayment(request, userId)
    PS->>Redis: 홀드 검증 + TTL 연장
    PS->>DB: Payment(READY) 저장
    PS-->>C: paymentKey, orderId(CARD)

    Note over C,K: Step 2: 결제 승인
    C->>PC: POST /api/payments/{key}/approve
    PC->>PS: approvePayment(key, userId)
    alt POINT 결제
        PS->>DB: users.point 차감 (PESSIMISTIC_WRITE)
    else CARD 결제
        PS->>PS: 토스 confirm API 호출
    end
    PS->>DB: Payment(APPROVED)
    PS-->>C: 200 OK

    Note over C,K: Step 3: 결제 완료 (예약 확정)
    C->>PC: POST /api/payments/{key}/complete
    PC->>PS: completePayment(key, userId)
    PS->>RS: confirm(holdToken, userId)
    RS->>Redis: 홀드 검증 + tryLock("lock:seat:{id}")
    RS->>DB: seat RESERVED, Reservation, kafka_outbox INSERT
    Note over RS: ApplicationEventPublisher 로 이벤트 등록(커밋 후 리스너 실행)
    RS-->>PS: ReservationResponse
    PS->>DB: Payment(COMPLETED, reservationId)
    PS->>K: PaymentComplete 직접 send (outbox 아님)
    PS-->>C: 200 OK

    Note over DB: 트랜잭션 커밋 완료 시점 이후
    L->>Redis: releaseHold (AFTER_COMMIT)
    Note over Sched,K: 고정 주기, 분산 락으로 단일 인스턴스 실행
    Sched->>DB: PENDING outbox 조회
    Sched->>K: RESERVATION_CONFIRMED, 성공 시 행 삭제

    Note over K: Consumer 알림/SSE 등
```

## 3. 보상 트랜잭션 (Saga)

```mermaid
sequenceDiagram
    participant PS as PaymentService
    participant RS as ReservationService
    participant DB as Database

    PS->>RS: confirm(holdToken, userId)
    RS-->>PS: ❌ 예외 발생 (홀드 만료, 좌석 경합 등)
    
    Note over PS: 보상 트랜잭션 실행
    alt POINT 결제
        PS->>DB: users.point += amount (포인트 환불)
    end
    PS->>DB: payment.status = CANCELED
    PS-->>PS: 원래 예외를 다시 throw
```

## 4. Kafka DLQ 흐름

```mermaid
sequenceDiagram
    participant P as Producer
    participant T as Topic
    participant C as Consumer
    participant DLT as Dead Letter Topic

    P->>T: 메시지 발행
    T->>C: 메시지 수신
    C->>C: 처리 시도 #1 실패
    Note over C: 1초 대기
    C->>C: 처리 시도 #2 실패
    Note over C: 1초 대기
    C->>C: 처리 시도 #3 실패
    Note over C: 최대 재시도 초과
    C->>DLT: 메시지 전송 (*.DLT 토픽)
    Note over DLT: 모니터링 → 수동 재처리
```

<a id="consistency-failure-scenarios"></a>

## 5. 정합성·실패 시나리오 (DB / Redis / Kafka)

예약 확정 이후 `RESERVATION_CONFIRMED` 는 **DB transactional outbox** 로 발행하고, 그 외 다수 이벤트는 **KafkaTemplate 직접 send** 입니다. 아래 표는 **코드 기준으로 남는 상태**를 정리한 것이며, 운영에서는 `kafka_outbox`·로그·DLT·메트릭을 함께 보면 됩니다.

### 5.1 예약 확정(`ReservationService.confirm`) 이후

| 시나리오 | DB | Redis 홀드 | Kafka `RESERVATION_CONFIRMED` | 사용자·알림 관점 |
|---------|----|------------|-------------------------------|------------------|
| 정상 | 예약 `CONFIRMED`, 좌석 `RESERVED` | 커밋 후 리스너에서 해제 | outbox → 전송 후 행 삭제 | 정상 |
| Kafka 일시 장애 | 동일 | 동일 | outbox `PENDING` 유지 → 스케줄러 재시도 | DB 예약은 됨, 알림·소비는 **지연** |
| Kafka 장기 장애·재시도 초과 | 동일 | 동일 | outbox `FAILED` (`lastError` 남음) | 예약은 DB에 있음, **이벤트만 영구 미전달 가능** → 운영 수동 |
| Redis `releaseHold` 실패(AFTER_COMMIT) | 이미 커밋됨 | **해제 실패 시 잔여 가능**(TTL까지) | outbox와 별개 | 좌석은 DB상 예약됨. Redis 잔여는 **엣지** |

#### Outbox 발행 재시도·최종 실패 (시퀀스)

```mermaid
sequenceDiagram
    participant Sched as OutboxScheduler
    participant DB as Database
    participant K as Kafka

    Sched->>DB: PENDING 행 조회
    Sched->>K: RESERVATION_CONFIRMED send
    K-->>Sched: 오류 또는 타임아웃
    Sched->>DB: publishAttempts 증가, lastError 저장
    Note over Sched: 다음 틱에서 동일 행 재시도
    Sched->>K: send 재시도
    alt 성공
        K-->>Sched: OK
        Sched->>DB: 행 삭제
    else maxPublishAttempts 초과
        Sched->>DB: status FAILED
        Note over DB: 수동 조치 대상
    end
```

#### 커밋 후 Redis 홀드 해제만 실패한 경우 (시퀀스)

```mermaid
sequenceDiagram
    participant DB as Database
    participant L as AfterCommitListener
    participant Redis as Redis

    Note over DB: 트랜잭션 커밋 완료 예약·좌석 반영
    L->>Redis: releaseHold(holdToken)
    Redis-->>L: 연결 실패 등
    Note over L: DB 롤백은 불가 AFTER_COMMIT
    Note over Redis: 잔여 홀드 데이터는 TTL까지 남을 수 있음
```

### 5.2 홀드 생성(`HoldService.createHold`) — outbox 없음

| 시나리오 | Redis 홀드 | DB 좌석 | Kafka `HOLD_CREATED` | 비고 |
|---------|------------|---------|----------------------|------|
| 정상 | 생성됨 | 보통 `AVAILABLE` 유지 | 발행 | 정상 |
| Redis 생성 후 Kafka만 실패(프로듀서 재시도 소진) | 생성됨 | 동일 | **유실 가능** | 선점은 Redis 유지, **알림/SSE 등 비동기만** 어긋날 수 있음 |

```mermaid
sequenceDiagram
    participant HS as HoldService
    participant Store as HoldStore
    participant K as Kafka

    HS->>Store: createHold 성공
    HS->>K: HOLD_CREATED publish
    Note over K: acks=all, retries=3, idempotence
    K-->>HS: 최종 실패 시
    Note over Store: 홀드는 이미 Redis에 존재
    Note over K: 메시지는 유실 가능 outbox 아님
```

### 5.3 홀드 취소·만료 스케줄 — Kafka 직접 send

| 시나리오 | Redis | Kafka (`HOLD_CANCELED` / `HOLD_EXPIRED`) | 비고 |
|---------|-------|------------------------------------------|------|
| 정상 | 해제·정리됨 | 발행 | 정상 |
| Redis 처리 후 Kafka 실패 | 이미 정리 | **유실 가능** | **사실**은 Redis 기준 반영, 알림만 누락 가능 |

### 5.4 결제 완료 후 `PaymentCompleteEvent`

| 시나리오 | 결제 DB | 예약 DB | Kafka `PaymentCompleteEvent` | 비고 |
|---------|---------|---------|------------------------------|------|
| 정상 | `COMPLETED` | 있음 | 직접 send 성공 | 정상 |
| 예약·결제 반영 후 이벤트만 실패 | `COMPLETED` | 있음 | **유실 가능** | DB 정합성 유지, **후속 비동기**만 불완전할 수 있음 |

### 5.5 결제 완료 중 예약 확정만 실패

| 시나리오 | 결제 DB | 예약 DB | Redis 홀드 | 사용자 응답 |
|---------|---------|---------|------------|-------------|
| `confirm` 예외 → `compensatePayment` 성공 | `CANCELED`(포인트 환불) | 없음(미커밋) | 확정 전이면 **홀드 유지** 가능 | HTTP 오류 + `ResponseStatusException` 메시지 |
| 보상 실패 | 불일치 위험 | - | - | 로그 수동 확인, 클라이언트는 실패 응답 가능 |

보상 흐름 시퀀스는 위 **§3 보상 트랜잭션** 참고.

### 5.6 재시도·멱등 (요약)

| 구분 | 어디서 재시도 | 비고 |
|------|----------------|------|
| HTTP | 서버가 자동 재호출하지 않음 | 클라이언트·프록시·LB |
| 결제 API 중복 | `Idempotency-Key` + `@Idempotent` | 동일 키 재전송 완화 |
| Kafka 프로듀서 | `retries=3`, `idempotence=true` | 일시 오류 완화, 무한 보장 아님 |
| `RESERVATION_CONFIRMED` | `KafkaOutboxPublishScheduler` + `maxPublishAttempts` | 초과 시 `FAILED` 고정 |
| 좌석 락 | `ticketing.lock.retry-*` | 홀드 생성 시 락 재시도 |

### 5.7 사용자에게 실패를 알리는 경로

서비스는 주로 `ResponseStatusException` / `BusinessException` 을 던지고, `GlobalExceptionHandler` 가 HTTP 상태와 JSON `ErrorResponse`(메시지·경로·시각 등)로 응답합니다. 프론트는 상태 코드와 `message` 를 매핑해 화면 문구를 구성하면 됩니다.
