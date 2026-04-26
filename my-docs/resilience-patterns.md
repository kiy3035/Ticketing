# 장애 대응 패턴 상세

이 프로젝트에 실제로 적용된 5가지 패턴: **멱등성 키 / Saga 보상 / 서킷브레이커 / Rate Limit / Outbox**.

---

## 1. 멱등성 키 (Idempotency Key)

### 문제 상황
```
Client → POST /payments/request → 네트워크 타임아웃
Client → (응답 못 받음) → 같은 요청 재전송
Server → 이미 Payment 생성됨 → 또 생성하면 이중 결제!
```

### 해결 — `@Idempotent` AOP

```java
@Idempotent(ttlSeconds = 86400)  // 24시간
@PostMapping("/request")
public PaymentResponse request(...) { ... }
```

`IdempotencyAspect.around()` 흐름:
1. 요청 헤더 `Idempotency-Key` 추출 (없으면 그냥 통과 — 하위 호환)
2. Redis `idempotency:{key}` 조회
3. **결과 캐시 있으면**: 캐시된 응답 반환 (로직 미실행)
4. **`__PROCESSING__` 마커면**: `IDEMPOTENCY_CONFLICT` (다른 요청 처리 중)
5. **없으면**: `setIfAbsent(key, "__PROCESSING__", ttl)` 으로 선점 → 로직 실행 → 결과 JSON 저장
6. 실패 시 `releaseKey(key)` — 재시도 허용

### 코드 위치
- `common.idempotency.Idempotent` — 어노테이션
- `common.idempotency.IdempotencyAspect` — AOP
- `common.idempotency.IdempotencyService` — Redis 키 관리

---

## 2. Saga 보상 트랜잭션 (REQUIRES_NEW)

### 문제 상황
```
PaymentService.completePayment():
1. payment.status = APPROVED (이미 포인트 차감됨, 별도 트랜잭션에서 커밋된 상태)
2. reservationService.confirm() ← 여기서 실패!
   - 홀드 만료
   - 좌석 이미 예약됨
   - DB 장애
3. outer @Transactional 롤백 → completePayment의 DB 변경만 롤백
   → 1단계 포인트 차감은 그대로 → 돈만 빠짐
```

### 해결 — `PaymentCompensationService` (REQUIRES_NEW)

```java
try {
    reservation = reservationService.confirm(...);
} catch (Exception e) {
    log.error("예약 확정 실패 → 보상 트랜잭션 실행");
    paymentCompensationService.compensateAfterReservationFailure(payment.getId());
    throw e;  // 원래 예외 재던짐
}
```

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void compensateAfterReservationFailure(Long paymentId) {
    Payment payment = paymentRepository.findWithLockById(paymentId)...;
    if (payment.getStatus() == CANCELED) return;  // 멱등
    if (payment.getStatus() != APPROVED) return;
    if (payment.getPaymentMethod() == POINT) {
        refundPoints(payment.getUserId(), payment.getAmount());
    }
    payment.setStatus(CANCELED);
    payment.setCanceledAt(now);
}
```

**REQUIRES_NEW의 의미**: outer 트랜잭션과 독립된 새 트랜잭션 → outer 롤백되어도 보상 결과는 별도 커밋. "포인트 환불 + 결제 CANCELED"는 반드시 DB에 남는다.

**왜 Choreography(코레오그래피) 인가?**
- Orchestrator(별도 조정자 서비스) 방식은 인프라 복잡도 증가
- 보상이 필요한 경계가 1곳(결제→예약)뿐이므로 단순 try-catch + REQUIRES_NEW 로 충분
- "포트폴리오 규모에서 적정 수준" 의 실용형 사가

---

## 3. 서킷브레이커 (Circuit Breaker — Resilience4j)

### 상태 전이
```
CLOSED (정상) ──실패율 50% 초과──▶ OPEN (차단)
    ▲                                    │
    │                              30초 경과
    │                                    ▼
    └────── 성공률 회복 ◀──── HALF_OPEN (시험)
```

### 설정값 (`application.properties` / `redisCircuitBreaker`)
```properties
resilience4j.circuitbreaker.instances.redisCircuitBreaker.sliding-window-size=10
resilience4j.circuitbreaker.instances.redisCircuitBreaker.failure-rate-threshold=50
resilience4j.circuitbreaker.instances.redisCircuitBreaker.wait-duration-in-open-state=30s
resilience4j.circuitbreaker.instances.redisCircuitBreaker.permitted-number-of-calls-in-half-open-state=3
resilience4j.circuitbreaker.instances.redisCircuitBreaker.slow-call-duration-threshold=1s
resilience4j.circuitbreaker.instances.redisCircuitBreaker.slow-call-rate-threshold=80
```

`slow-call-duration-threshold=1s`: Redis 명령 타임아웃(2s) 보다 짧게 두어 **타임아웃 전에 slow call 로 감지** → 서킷이 더 빠르게 열림.

### `RedisCircuitBreakerExecutor` (래퍼)

```java
redisCb.execute(
    "hold.getHold",                    // 로그용 op 이름
    () -> redisTemplate.get(tokenKey), // action
    () -> null                         // fallback (Redis 죽었을 때 반환값)
);
```

- **CLOSED**: action 실행 → 성공/실패/slow 통계 누적
- **OPEN**: action 호출조차 안 하고 즉시 fallback 반환 (`CallNotPermittedException` 캐치)
- **임계 초과 → OPEN 전환**: `failure-rate-threshold=50%` 또는 `slow-call-rate-threshold=80%`

### 적용 위치
- `HoldStore` — 모든 Redis 호출 (createHold, getHold, releaseHold 등)
- `QueueService` — 모든 Redis 호출 (enterQueue, getRank, allowEntry 등)

다른 Redis 사용처(`NotificationService`, `IdempotencyService`, `RateLimitService`, `TokenBlacklistService`, `ActiveUserTracker`)는 현재 미적용 — 핵심 경로 우선 적용 후 점진 확대 가능.

### Fallback 전략
- **쓰기 경로 (생성·진입)**: `0L` / `false` 반환 → 호출 측에서 사용자에게 "잠시 후 다시 시도" 응답
- **조회 경로**: `null` / `Set.of()` / `0L` → "데이터 없음" 처럼 처리 → 화면이 빈 상태로 그려짐 (서비스 부분 가용)

---

## 4. Rate Limiting

### 알고리즘: Sliding Window (Redis Sorted Set + Lua)

```
시간 ──────────────────────────────────▶
      │  윈도우 (1초)  │
      │ ■ ■ ■ ■ ■ ■ ■ │ ← 7개 (한도: 10)
      │                │
         ZREMRANGEBYSCORE 윈도우 밖 제거
         ZCARD 현재 수 확인
         한도 이내면 ZADD, 초과면 거부
```

전체를 Lua 한 스크립트로 → 경쟁 조건 차단 (`06-redis-kafka-reference.md` §2.4 참고).

### 적용 — `@RateLimit` AOP

```java
@RateLimit(maxRequests = 5, windowSeconds = 1)
@PostMapping("/payments/request")
public PaymentResponse request(...) { ... }
```

`RateLimitAspect`:
- 사용자 식별자: 인증된 사용자는 `user:{username}`, 미인증이면 `ip:{X-Forwarded-For 또는 RemoteAddr}`
- 한도 초과 시 `BusinessException(RATE_LIMIT_EXCEEDED)` → `GlobalExceptionHandler` 가 429 응답

### 글로벌 설정
- `ticketing.rate-limit.enabled=true`
- 기본값 `requests-per-second=10`, `window-seconds=1`

---

## 5. Transactional Outbox (`RESERVATION_CONFIRMED`)

**문제**: 예약 row 는 커밋됐는데 Kafka 전송만 실패하면, 다운스트림(알림·연동)이 영원히 모를 수 있다. 반대로 send만 성공하고 DB가 롤백되면 "유령 이벤트".

**해결**:
- `ReservationService.confirm()` 안에서 `KafkaOutboxService.enqueueSeatHoldEvent` 로 같은 트랜잭션에 `kafka_outbox` INSERT.
- 브로커로의 send는 `KafkaOutboxPublishScheduler` 가 비동기. 성공 시 행 DELETE, 실패 시 재시도(25회) 후 FAILED.

**왜 `RESERVATION_CONFIRMED` 만 outbox?**
- "예약이 커밋되면 반드시 알려야 한다"는 **강한 발행 보장**이 필요한 유일한 케이스
- `HOLD_CREATED`, `HOLD_CANCELED`, `HOLD_EXPIRED`, `PaymentComplete` 는 알림 지연/누락이 비즈니스에 치명적이지 않음 → 직접 `KafkaTemplate.send` (간단성 우선)
- 트레이드오프: 직접 send 경로는 브로커 장애 시 "DB 반영, 이벤트 누락" 구간이 생길 수 있음. 필요시 같은 패턴으로 확장 가능.

자세한 동작은 `05-schedulers.md` §5, `06-redis-kafka-reference.md` §3 참고.

---

## 6. 장애 시나리오 정리

| 장애 | 영향 | 대응 |
|------|------|------|
| Redis 다운 | 락/홀드/대기열/캐시 불능 | `ticketingDatastores` 헬스 DOWN → ALB가 트래픽 제거 검토. 서킷브레이커가 즉시 fail-fast로 응답 시간 보호 |
| Kafka 다운 | 직접 send 경로(알림 등) 지연/유실 가능 | `RESERVATION_CONFIRMED`는 outbox에 남음 → 브로커 복구 후 스케줄러가 재시도 |
| Outbox 반복 실패 | `publish_attempts` 25회 초과 → `FAILED` | 모니터링 알람·수동 재처리·원인 조사(브로커/페이로드) |
| DB 다운 | 전체 서비스 불능 | 헬스체크 DOWN → ALB 제거. RDS 페일오버 대기 |
| 외부 PG (토스) 장애 | 카드 결제 불가 | 포인트 결제는 정상. 카드는 `tossPaymentsClient.confirmPayment` 예외 → 결제 APPROVED 안 됨 |
| 앱 서버 OOM | 해당 인스턴스 불능 | ALB 헬스체크 실패 시 다른 인스턴스로 라우팅 (다른 인스턴스가 있다면) |

---

## 7. 면접 한 줄

- **멱등성**: HTTP `Idempotency-Key` 헤더 + Redis `__PROCESSING__` 마커로 중복 결제 차단
- **Saga**: 결제→예약 경계의 보상 트랜잭션을 `REQUIRES_NEW` 로 분리해 outer 롤백과 무관하게 보상 커밋 보존
- **서킷브레이커**: `RedisCircuitBreakerExecutor` 로 핵심 Redis 경로(Hold/Queue) 만 우선 감싸서 fail-fast + fallback
- **Rate Limit**: Lua 기반 Sliding Window — 사용자/IP 단위로 결제 API 등 보호
- **Outbox**: `RESERVATION_CONFIRMED` 만 outbox로 적재해 DB 커밋과 발행 의무를 묶음. 발행 실패는 재시도→FAILED로 운영 가시화
