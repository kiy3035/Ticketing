# 06. Redis·Kafka 심화 레퍼런스

코드를 읽을 때 **어느 키가 어디서 쓰이는지**, **Kafka가 어느 경로로 나가는지**를 한곳에서 따라가기 위한 공부용 정리.

---

## 1. Redis 키 카탈로그

### 1.1 대기열·홀드·락 (핵심 도메인)

| 키 패턴 | 타입 | TTL / 정리 | 용도 | 코드 위치 |
|---------|------|------------|------|-----------|
| `queue:concert:{concertId}` | ZSet | TTL 없음 → `QueueCleanupScheduler` | 멤버=토큰, score=진입 시각(ms) | `QueueService` |
| `queue:token:{token}` | String(JSON) | `ticketing.queue.token-ttl-seconds` | `{userId, concertId, enteredAt}` | `QueueService` |
| `queue:allowed:{token}` | String(JSON) | 위와 동일 | 입장 허용 메타 `{concertId, allowedAt}` | `QueueService.allowEntry` |
| `hold:seat:{seatId}` | String | `ticketing.hold.ttl-seconds` (연장 시 갱신) | 좌석 → 홀드 토큰 | `HoldStore` Lua |
| `hold:token:{holdToken}` | String(JSON) | 동일 | 토큰 → `HoldInfo` JSON | `HoldStore` |
| `hold:expires` | ZSet | TTL 없음 → 만료 스캔 후 ZREM | score=만료시각(ms), member=payload JSON | `HoldStore`, `HoldCleanupScheduler` |
| `hold:user:{userId}` | Set | TTL 없음 | 사용자별 활성 홀드 토큰 인덱스 | `HoldStore.getHoldsByUser`에서 토큰 키 없으면 SREM |
| `lock:seat:{seatId}` | String | `ticketing.lock.ttl-seconds` (기본 5초) | 좌석 단위 분산 락. 값=UUID | `RedisLockService` |
| `lock:batch:queue-process` | String | 15s | 대기열 입장 배치 단일 실행 | `QueueProcessingScheduler` |
| `lock:batch:queue-cleanup` | String | (코드 default) | 대기열 유령 토큰 정리 | `QueueCleanupScheduler` |
| `lock:batch:hold-cleanup` | String | 90s | 만료 홀드 정리 | `HoldCleanupScheduler` |
| `lock:batch:refund` | String | 360s | 취소 공연 환불 배치 | `RefundForCancelledConcertScheduler` |
| `lock:batch:kafka-outbox` | String | 120s | Outbox 발행 배치 단일 실행 | `KafkaOutboxPublishScheduler` |

### 1.2 인증·알림·접속자·캐시·공통

| 키 패턴 | 타입 | TTL / 정리 | 용도 | 코드 위치 |
|---------|------|------------|------|-----------|
| `jwt:bl:{jti}` | String | Access 만료까지 | 로그아웃된 Access JWT 블랙리스트 | `TokenBlacklistService` |
| `notify:user:{userId}` | List | 7일 | 알림 LPUSH + LTRIM 50건 | `NotificationService` |
| `active:users` | ZSet | 활동 시각 score | 접속자 추적 | `ActiveUserTracker` |
| `concert:list:*` (`CacheNames.CONCERT_LIST`) | String(JSON) | 5분 (`@Cacheable`) | 콘서트 목록 캐시 | `ConcertService`, `RedisConfig` |
| `queue:status:available-seats:*` (`CacheNames.QUEUE_STATUS_AVAILABLE_SEATS`) | String | 2초 (`ticketing.cache.queue-status-available-seats-ttl-seconds`) | 잔여석 집계 캐시 | `SeatService.countAvailableSeatsForQueueStatus` (홀드/예약/만료 시 evict) |
| `idempotency:{key}` | String | `@Idempotent.ttlSeconds` | HTTP 멱등 결과 캐시·`__PROCESSING__` 마커 | `IdempotencyService` |
| `ratelimit:{identifier}` | ZSet | Lua 내 `EXPIRE(window+1)` | 슬라이딩 윈도 레이트 리밋 | `RateLimitService` |

> **Spring Session은 사용하지 않는다.** 인증은 JWT(Access + Refresh) — `ticketing:sessions:*` 같은 세션 키는 없다.

### 1.3 `hold:user:{userId}` 를 따로 둔 이유

- **이유**: "내 홀드 목록" API가 매번 `hold:expires` 전체를 스캔하면 O(전역 홀드 수). 사용자 인덱스로 조회 비용을 사용자 단위로 제한.
- **정리**: 토큰 키(`hold:token:*`)는 TTL로 사라져도 Set 멤버는 남는다. `HoldStore.getHoldsByUser` 가 멤버마다 `hold:token:{token}` GET → 없으면 `SREM` 으로 자가 정리. release/expire 경로에서도 Set에서 토큰 제거.

---

## 2. Lua 스크립트 모음

### 2.1 `HoldStore.CREATE_SCRIPT` — 홀드 생성 원자성

```lua
if redis.call('EXISTS', KEYS[1]) == 1 then
    return 0
end
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])  -- hold:seat:{seatId} = holdToken
redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[2])  -- hold:token:{token} = HoldInfo JSON
redis.call('ZADD', KEYS[3], ARGV[4], ARGV[3])       -- hold:expires score=만료ms member=payload
return 1
```

### 2.2 `HoldStore.RELEASE_SCRIPT` — 홀드 해제 (안전한 삭제)

```lua
if redis.call('GET', KEYS[1]) == ARGV[1] then
    redis.call('DEL', KEYS[1])  -- 본인 토큰일 때만 hold:seat 삭제
end
redis.call('DEL', KEYS[2])      -- hold:token 삭제
redis.call('ZREM', KEYS[3], ARGV[2])  -- hold:expires 멤버 제거
return 1
```

세 키를 분리해서 처리하는 이유: cleanup과 confirm이 동시에 같은 토큰을 release할 때 seat 키가 다른 토큰 소유면 건드리지 않아야 한다.

### 2.3 `RedisLockService.UNLOCK_SCRIPT` — 락 안전 해제

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
```

내 UUID 토큰일 때만 DEL → TTL 만료 후 다른 워커가 잡은 락을 실수로 해제하지 않음.

### 2.4 `RateLimitService.RATE_LIMIT_SCRIPT` — Sliding Window

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

`ZREMRANGEBYSCORE → ZCARD → ZADD + EXPIRE` 가 한 스크립트 안. 분리하면 "한도 넘었는데 ZADD 성공" 레이스가 난다.

---

## 3. MySQL `kafka_outbox` (Transactional Outbox)

Flyway `V4__kafka_outbox.sql`:

```sql
CREATE TABLE kafka_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    partition_key VARCHAR(255) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,        -- PENDING / FAILED
    created_at DATETIME(6) NOT NULL,
    publish_attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1024) NULL,
    INDEX idx_kafka_outbox_status_id (status, id)
);
```

| 컬럼 | 의미 |
|------|------|
| `topic` | 발행할 토픽 (현재 `ticketing.kafka.hold-topic` = `ticketing.seat-hold-events`) |
| `partition_key` | Kafka 파티션 키 (코드에서는 `seatId` 문자열) |
| `payload_json` | `SeatHoldEvent` JSON |
| `status` | `PENDING` / `FAILED` (`KafkaOutboxStatus`) |
| `publish_attempts` | 실패 시마다 증가, `max-publish-attempts(25)` 초과 시 `FAILED` |
| `last_error` | 마지막 예외 메시지 (1000자로 잘라서 저장) |

**적재 측** (`KafkaOutboxService.enqueueSeatHoldEvent`):
- 호출은 **`ReservationService.confirm()`의 `@Transactional` 과 같은 트랜잭션**에 참여 (전파 `REQUIRED`)
- 브로커 장애가 비즈니스 DB 커밋을 막지 않게 — send는 스케줄러에만

**발행 측** (`KafkaOutboxPublishScheduler`):
- `fixedDelay = ticketing.outbox.publish-interval-ms` (500ms)
- `lock:batch:kafka-outbox` 분산 락
- `TransactionTemplate.executeWithoutResult` (스케줄 메서드 자기호출 회피)
- `kafkaTemplate.send(...).get(15s)` → 성공 시 **`repository.delete(row)`** (SENT 상태 컬럼 없음, 그냥 DELETE)
- 실패 시 `publishAttempts++`, 한도 초과 시 `FAILED` 로 남겨 운영 개입 대상

---

## 4. Kafka 토픽·프로듀서·컨슈머

### 4.1 토픽과 발행 경로

| 토픽 | 이벤트 / 페이로드 | 프로듀서 경로 |
|------|-------------------|---------------|
| `ticketing.seat-hold-events` | `HOLD_CREATED`, `HOLD_CANCELED`, `HOLD_EXPIRED` | `SeatHoldEventPublisher` → `KafkaTemplate.send` (직접) |
| 동일 | `RESERVATION_CONFIRMED` | **`KafkaOutboxPublishScheduler`** 가 outbox 행을 읽어 `SeatHoldEvent` 역직렬화 후 send |
| `ticketing.payment-complete` | `PaymentCompleteEvent` | `PaymentCompleteEventPublisher` → 직접 send (outbox 미경유) |

**중요**: `RESERVATION_CONFIRMED` 는 **`ReservationConfirmedEventListener` 에서 발행하지 않는다.** 리스너(AFTER_COMMIT)는 **`holdStore.releaseHold` 만** 수행. 발행은 outbox 스케줄러 담당.

### 4.2 컨슈머·그룹

| 리스너 | 토픽 | 그룹 ID | 팩토리 |
|--------|------|---------|--------|
| `SeatHoldEventConsumer` | `ticketing.seat-hold-events` | `spring.kafka.consumer.group-id` (기본 `ticketing-notification`) | `seatHoldKafkaListenerFactory` |
| `PaymentCompleteEventConsumer` | `ticketing.payment-complete` | **`ticketing-payment-notification`** (리스너에 명시) | `paymentCompleteKafkaListenerFactory` |

`SeatHoldEventConsumer` 는 `HOLD_EXPIRED`, `RESERVATION_CONFIRMED` 만 처리 — 다른 타입은 조기 return.

### 4.3 직렬화

- Producer·Consumer 모두 **`JsonSerializer` / `JsonDeserializer`** + 공통 `ObjectMapper` 빈
- `JsonDeserializer`:
  - `setRemoveTypeHeaders(true)` — Producer가 보내는 `__TypeId__` 헤더에 의존하지 않음
  - `addTrustedPackages("com.inyoung.ticketing.*")` — 보안 화이트리스트
  - `setUseTypeMapperForKey(false)` — key는 String

### 4.4 DLT (Dead Letter Topic)

`KafkaConfig.createErrorHandler`:
- `DeadLetterPublishingRecoverer` + `DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L))` → 1초 간격 3회 재시도 후 **`원토픽.DLT`** 로 전송
- 예: `ticketing.seat-hold-events.DLT`, `ticketing.payment-complete.DLT`
- 운영에서는 DLT 모니터링·수동 재처리 전제

### 4.5 프로듀서 안전 설정 (`application.properties`)

```
spring.kafka.producer.acks=all
spring.kafka.producer.retries=3
spring.kafka.producer.properties.enable.idempotence=true
```

브로커 쪽 중복 제거에 가깝게 동작 — 컨슈머는 여전히 **at-least-once** 가정으로 멱등(상태 가드)을 둠.

### 4.6 Virtual Thread 로 리스너 실행

`KafkaConfig.virtualThreadExecutor()`:
```java
SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor(prefix);
executor.setVirtualThreads(true);
factory.getContainerProperties().setListenerTaskExecutor(executor);
```

이메일/SMS·DB 조회 등 **I/O 대기 동안 캐리어 스레드 점유 방지**. `kafka-seat-hold-`, `kafka-payment-` 두 풀 분리.

### 4.7 새 토픽·새 이벤트 추가 체크리스트

1. `TicketingProperties` / `application.properties` 에 토픽명·배치 크기 추가
2. `KafkaConfig` 에 Producer/Consumer Factory + `ListenerContainerFactory` 추가, **동일 DLQ ErrorHandler** 연결
3. 발행 서비스(`*Publisher`) 와 `@KafkaListener` 구현
4. **DB 와 반드시 같이 커밋돼야 하는 발행**이면 outbox 패턴 검토 (현재는 `RESERVATION_CONFIRMED` 만)

---

## 5. 메모리·운영 (Redis)

- Docker Compose에서 Redis: `maxmemory 400mb`, `maxmemory-policy allkeys-lru`, `save ""` (RDB 비활성)
- **TTL 없는 ZSet**(`queue:concert:*`, `hold:expires`)도 eviction 대상. 데이터는 휘발성이며 DB가 source of truth.
- 알림 리스트는 `LTRIM 0 49` 로 길이 50 상한 → 메모리 폭주 방지.
- 부하 테스트용 짧은 TTL 과 운영 권장 TTL 을 `application.properties` 주석으로 구분.

### `volatile-lru` 가 아닌 `allkeys-lru` 인 이유
TTL 없는 ZSet (`hold:expires`, `queue:concert:*`) 이 eviction 대상에서 빠지면, 스케줄러 정리가 밀릴 때 정상 키만 밀려나는 역전 현상이 발생. 모든 데이터가 휘발성이므로 `allkeys-lru` 로 전부 후보화.

---

## 6. 코드 네비게이션 (빠른 점프)

| 찾는 것 | 파일 |
|---------|------|
| Redis 홀드 Lua | `hold.store.HoldStore` |
| 좌석/배치 락 | `lock.RedisLockService` |
| 대기열 키 | `queue.service.QueueService` |
| Outbox 적재 | `outbox.KafkaOutboxService`, `outbox.KafkaOutbox` |
| Outbox 발행 | `scheduler.KafkaOutboxPublishScheduler` |
| 커밋 후 홀드 해제 | `reservation.event.ReservationConfirmedEventListener` |
| 홀드 이벤트 직접 발행 | `hold.event.SeatHoldEventPublisher` |
| 결제 완료 이벤트 | `payment.event.PaymentCompleteEventPublisher` / `PaymentCompleteEventConsumer` |
| Redis 서킷브레이커 | `common.resilience.RedisCircuitBreakerExecutor` |
| 멱등 AOP | `common.idempotency.IdempotencyAspect` / `IdempotencyService` |
| Rate Limit AOP | `common.ratelimit.RateLimitAspect` / `RateLimitService` |
| JWT Access 블랙리스트 | `auth.jwt.TokenBlacklistService` |
| Refresh jti 저장·폐기 (DB) | `auth.jwt.RefreshTokenPersistenceService` |
| 헬스 (DB+Redis) | `health.TicketingDatastoresHealthIndicator` |
