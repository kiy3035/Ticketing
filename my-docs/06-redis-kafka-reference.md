# 06. Redis·Kafka 심화 (키·Outbox·컨슈머·Lua)

코드를 읽을 때 **어느 키가 어디서 쓰이는지**, **Kafka 가 어떤 경로로 나가는지**를 한곳에서 따라가기 위한 공부용 정리다.  
요약·다이어그램은 [`docs/data.md`](../docs/data.md), 실패 시나리오 표는 [`docs/sequence-diagrams.md` §5](../docs/sequence-diagrams.md#consistency-failure-scenarios) 를 병행하면 좋다.

> 이 문서는 예전에 나뉘어 있던 **`redis-patterns.md`**, **`kafka-consumer-guide.md`** 내용을 합치고 최신 소스(outbox, 스케줄러 5종)에 맞게 고친 것이다.

---

## 1. Redis 키 전체 카탈로그

### 1.1 대기열·홀드·락·세션 (핵심 도메인)

| 키 패턴 | 타입 | TTL / 정리 | 용도 | 대표 사용처 |
|---------|------|------------|------|-------------|
| `queue:concert:{concertId}` | ZSet | 없음 → `QueueCleanupScheduler` 가 유령 멤버 `ZREM` | 멤버=토큰, score=진입 시각(ms) | `QueueService` |
| `queue:token:{token}` | String(JSON) | `ticketing.queue.token-ttl-seconds` | userId, concertId, enteredAt | `QueueService` |
| `queue:allowed:{token}` | String(JSON) | 위와 동일 | 입장 허용 메타 | `QueueService`, `QueueController` |
| `hold:seat:{seatId}` | String | `ticketing.hold.ttl-seconds` (연장 시 갱신) | 좌석→홀드 토큰 | `HoldStore` Lua |
| `hold:token:{holdToken}` | String(JSON) | 동일 | `HoldInfo` 전체 | `HoldStore` |
| `hold:expires` | ZSet | 없음 → 만료 스캔 후 `ZREM` | score=만료시각(ms), member=payload JSON | `HoldStore`, `HoldCleanupScheduler` |
| `hold:user:{userId}` | Set | 키 자체 TTL 없음 | 활성 홀드 토큰 목록 | `HoldStore` — `getHoldsByUser` 에서 토큰 키 없으면 Set 에서 제거 |
| `lock:seat:{seatId}` | String | `ticketing.lock.ttl-seconds` | 좌석 단위 분산 락 값=UUID | `RedisLockService` |
| `lock:batch:queue-process` | String | 스케줄러 락 TTL(예: 15s) | 대기열 입장 배치 단일 실행 | `QueueProcessingScheduler` |
| `lock:batch:queue-cleanup` | String | 배치 락 TTL | 대기열 유렸 토큰 정리 | `QueueCleanupScheduler` |
| `lock:batch:hold-cleanup` | String | 배치 락 TTL | 만료 홀드 정리 | `HoldCleanupScheduler` |
| `lock:batch:refund` | String | 길게(예: 360s) | 취소 공연 환불 배치 | `RefundForCancelledConcertScheduler` |
| `lock:batch:kafka-outbox` | String | 120s | Outbox 발행 배치 단일 실행 | `KafkaOutboxPublishScheduler` |
| `ticketing:sessions:*` | Hash 등 | Spring Session 설정(기본 30분) | 로그인 세션 | `spring-session-data-redis` |

### 1.2 알림·접속자·캐시·공통 인프라

| 키 패턴 | 타입 | TTL / 정리 | 용도 | 대표 사용처 |
|---------|------|------------|------|-------------|
| `notify:user:{userId}` | List | 7일 | 알림 목록 LPUSH, `LTRIM` 으로 최대 50건 | `NotificationService` |
| `active:users` | ZSet | 항목별 score 기반(활동 시각) | 접속자 추적 | `ActiveUserTracker` |
| `concert:list:*` (`CacheNames.CONCERT_LIST`) | String(JSON) | 5분 등 `@Cacheable` 설정 | 콘서트 목록 캐시 | `ConcertService`, `common.cache.CacheKeyConfig` |
| `idempotency:{key}` | String | `IdempotencyAspect` 에서 지정(예: 24h) | HTTP 멱등 응답 캐시·PROCESSING 마커 | `IdempotencyService` |
| `ratelimit:{identifier}` | ZSet | Lua 내 `EXPIRE(window+1)` | 슬라이딩 윈도 레이트 리밋 | `RateLimitService` |

### 1.3 `hold:user:{userId}` 를 따로 둔 이유와 정리 방식

- **이유**: "내 홀드 목록" API 가 매번 `hold:expires` 전체를 스캔하면 O(전역 홀드 수)가 된다. 사용자 ID 로 인덱스를 걸어 두면 조회 비용이 사용자 단위로 제한된다.
- **정리**: 토큰 키(`hold:token:*`)는 TTL 로 사라져도 Set 멤버는 남을 수 있다. `HoldStore.getHoldsByUser` 는 멤버마다 `hold:token:{token}` GET 을 해 보고, 없으면 `SREM` 으로 Set 을 정리한다. `releaseHold` / `releaseByPayload` 시에도 Set 에서 해당 토큰을 제거한다.

---

## 2. Rate limiting — Lua 원자성 (`RateLimitService`)

슬라이딩 윈도는 **ZREMRANGEBYSCORE → ZCARD → (허용 시) ZADD + EXPIRE** 를 한 스크립트로 묶어야 한다.  
중간에 다른 요청이 끼어들면 "한도를 넘었는데도 ZADD 성공" 같은 레이스가 난다.

```lua
local key = KEYS[1]
local window = tonumber(ARGV[1])
local maxRequests = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local windowStart = now - window * 1000
redis.call('ZREMRANGEBYSCORE', key, 0, windowStart)
local count = redis.call('ZCARD', key)
if count < maxRequests then
    redis.call('ZADD', key, now, now .. ':' .. math.random(1000000))
    redis.call('EXPIRE', key, window + 1)
    return 1
end
return 0
```

- **키**: `ratelimit:` + 식별자(username 또는 IP 등).
- **적용**: `@RateLimit` → `RateLimitAspect` → 위 서비스.

---

## 3. MySQL `kafka_outbox` (Transactional Outbox)

Flyway `V4__kafka_outbox.sql` 에 정의된다. **예약 확정 시 `RESERVATION_CONFIRMED` 만** 이 테이블을 탄다.

| 컬럼 | 의미 |
|------|------|
| `topic` | 발행할 토픽 (현재 `ticketing.kafka.hold-topic` = `ticketing.seat-hold-events`) |
| `partition_key` | Kafka 파티션 키 (코드에서는 `seatId` 문자열) |
| `payload_json` | `SeatHoldEvent` JSON |
| `status` | `PENDING` / `FAILED` (`KafkaOutboxStatus`) |
| `publish_attempts` | 실패 시마다 증가, `max-publish-attempts` 초과 시 `FAILED` |
| `last_error` | 마지막 예외 메시지(길이 제한 저장) |

**발행 측** (`KafkaOutboxPublishScheduler`):

- `fixedDelay` = `ticketing.outbox.publish-interval-ms` (기본 500ms).
- `lock:batch:kafka-outbox` 로 멀티 인스턴스 중 하나만 실행.
- `TransactionTemplate` 으로 배치 단위 트랜잭션(스케줄 메서드는 프록시 밖이라 `@Transactional` 자기호출이 안 먹히는 문제 회피).
- `kafkaTemplate.send(...).get(15, TimeUnit.SECONDS)` 로 **전송 완료를 기다린 뒤** `repository.delete(row)` — 성공 시 행은 **삭제**된다(SENT 상태 컬럼 없음).
- 실패 시 `publishAttempts` 증가, 재시도; 한도 초과 시 `FAILED` 로 남겨 운영 개입 대상으로 둔다.

**적재 측** (`KafkaOutboxService.enqueueSeatHoldEvent`):

- 호출은 **`ReservationService.confirm()` 의 `@Transactional` 과 같은 트랜잭션**에 참여해야 한다(기본 전파 `REQUIRED`).
- 브로커 장애가 **비즈니스 DB 커밋을 막지 않게** send 는 스케줄러에만 둔다.

---

## 4. Kafka 토픽·프로듀서·컨슈머

### 4.1 토픽과 이벤트

| 토픽 (실제 문자열) | 이벤트 / 페이로드 | 프로듀서 경로 |
|--------------------|-------------------|---------------|
| `ticketing.seat-hold-events` | `HOLD_CREATED`, `HOLD_CANCELED`, `HOLD_EXPIRED` | `SeatHoldEventPublisher` → `KafkaTemplate.send` (직접) |
| 동일 | `RESERVATION_CONFIRMED` | **`KafkaOutboxPublishScheduler`** 가 outbox 행을 읽어 `SeatHoldEvent` 역직렬화 후 send |
| `ticketing.payment-complete` | `PaymentCompleteEvent` | `PaymentCompleteEventPublisher` → 직접 send (outbox 아님) |

**주의 (예전 문서와의 차이)**:

- `RESERVATION_CONFIRMED` 는 **`ReservationConfirmedEventListener` 에서 발행하지 않는다.**
- 리스너(`AFTER_COMMIT`)는 **`holdStore.releaseHold` 만** 수행한다.
- `confirm()` 트랜잭션 안에서 `kafkaOutboxService.enqueueSeatHoldEvent(...)` 로 **outbox INSERT** 까지 같이 커밋된다.

### 4.2 컨슈머·그룹

| 리스너 | 토픽 | 그룹 ID | 팩토리 |
|--------|------|---------|--------|
| `SeatHoldEventConsumer` | `ticketing.seat-hold-events` | `application.properties` 의 `spring.kafka.consumer.group-id` (기본 `ticketing-notification`) | `seatHoldKafkaListenerFactory` |
| `PaymentCompleteEventConsumer` | `ticketing.payment-complete` | **`ticketing-payment-notification`** (리스너에 명시) | `paymentCompleteKafkaListenerFactory` |

`SeatHoldEventConsumer` 는 타입이 `HOLD_EXPIRED`, `RESERVATION_CONFIRMED` 인 것만 처리해 알림·SSE 에 반영한다(다른 타입은 조기 return).

### 4.3 직렬화 / 역직렬화

- Producer·Consumer 모두 **`JsonSerializer` / `JsonDeserializer`** + 공통 `ObjectMapper` 빈.
- `JsonDeserializer`: `setRemoveTypeHeaders(true)`, `addTrustedPackages("com.inyoung.ticketing.*")`, `setUseTypeMapperForKey(false)` 로 타입 헤더 의존을 줄인다.
- 과거에 Consumer 만 `StringDeserializer` + 수동 파싱이면 역직렬화 불일치가 났던 이슈가 있다(`my-docs/troubleshooting.md` 참고).

### 4.4 DLQ (Dead Letter)

`KafkaConfig.createErrorHandler`:

- `DeadLetterPublishingRecoverer` + `DefaultErrorHandler(..., new FixedBackOff(1000L, 3L))` → 최대 3회 재시도(1초 간격) 후 **`원토픽.DLT`** 로 전송 (예: `ticketing.seat-hold-events.DLT`).
- 운영에서는 DLT 모니터링·수동 재처리를 전제로 한다.

### 4.5 프로듀서 안전 설정 (`application.properties`)

- `spring.kafka.producer.acks=all`
- `spring.kafka.producer.retries=3`
- `spring.kafka.producer.properties.enable.idempotence=true`

**의미**: 브로커 쪽 중복 제거에 가깝게 동작하지만, 컨슈머는 여전히 **at-least-once** 를 가정하고 멱등(알림 플래그 등)을 두는 편이 안전하다.

### 4.6 Virtual Thread 로 리스너 실행

`KafkaConfig` 에서 `SimpleAsyncTaskExecutor` + `setVirtualThreads(true)` 를 `setListenerTaskExecutor` 에 넘긴다.  
이메일/SMS·DB 조회 등 **I/O 대기 동안 캐리어 스레드를 점유하지 않도록** 하기 위함이다. 상세는 [`virtual-threads-guide.md`](virtual-threads-guide.md) §4.

### 4.7 새 토픽·새 이벤트를 넣을 때 체크리스트

1. `TicketingProperties` / `application.properties` 에 토픽명·배치 크기 등 추가.
2. `KafkaConfig` 에 Producer/Consumer Factory 및 `ListenerContainerFactory` 추가, **동일한 DLQ ErrorHandler** 연결.
3. 발행 서비스와 `@KafkaListener` 구현.
4. **DB 와 반드시 같이 커밋돼야 하는 발행**이면 outbox 테이블·스케줄러 패턴을 검토(이 프로젝트는 `RESERVATION_CONFIRMED` 에만 적용).

---

## 5. 메모리·운영 (Redis)

- Docker 예시: `maxmemory` + `allkeys-lru` — **TTL 없는 ZSet**(`queue:concert:*`, `hold:expires`)도 eviction 대상이 될 수 있다. 데이터는 휘발성이며 DB 가 최종 진실이다.
- 홀드·락 TTL 은 `application.properties` 의 부하테스트용 짧은 값과 운영 권장 값을 주석으로 구분해 두었다.
- 알림 리스트는 길이 상한(`LTRIM`)으로 메모리 폭주를 제한한다.

---

## 6. 코드 네비게이션 (빠른 점프)

| 찾는 것 | 파일 |
|---------|------|
| Redis 홀드 Lua | `hold.store.HoldStore` |
| 좌석/배치 락 | `lock.RedisLockService` |
| 대기열 키 | `queue.QueueService` |
| Outbox 적재 | `outbox.KafkaOutboxService`, `outbox.KafkaOutbox` |
| Outbox 발행 | `scheduler.KafkaOutboxPublishScheduler` |
| 커밋 후 홀드 해제 | `reservation.event.ReservationConfirmedEventListener` |
| 홀드 이벤트 직접 발행 | `hold.event.SeatHoldEventPublisher` |
| 결제 완료 이벤트 | `payment.event.PaymentCompleteEventPublisher` / `PaymentCompleteEventConsumer` |
