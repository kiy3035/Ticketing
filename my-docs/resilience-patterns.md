# 장애 대응 패턴 상세

## 1. 멱등성 키 (Idempotency Key)

### 문제 상황
```
Client → POST /payments/request → 네트워크 타임아웃
Client → (응답을 못 받음) → 같은 요청 재전송
Server → 이미 Payment가 생성됨 → 또 생성하면 이중 결제!
```

### 해결
```
요청 Header: Idempotency-Key: abc-123-def
1. Redis에 "idempotency:abc-123-def" 조회
2. 없으면 → "PROCESSING" 마커 저장 → 로직 실행 → 결과 저장
3. 있으면 → 캐시된 결과 반환 (로직 미실행)
4. PROCESSING이면 → 409 Conflict (다른 요청이 처리 중)
5. 실패 시 → 키 삭제 (재시도 허용)
```

### AOP 구현 포인트
- `@Idempotent` 어노테이션 → `IdempotencyAspect`가 가로챔
- 헤더가 없으면 멱등성 체크 없이 통과 (하위 호환)
- TTL 24시간 → 24시간 후 같은 키로 새 요청 가능

## 2. 보상 트랜잭션 (Saga Pattern)

### 문제 상황
```
PaymentService.completePayment():
1. payment.status = APPROVED (이미 포인트 차감됨)
2. reservationService.confirm() ← 여기서 실패!
   - 홀드 만료
   - 좌석 이미 예약됨
   - DB 장애
3. 포인트는 이미 차감되었는데 예약은 안 됨 → 돈만 빠짐!
```

### 해결 (Choreography Saga)
```java
try {
    reservation = reservationService.confirm(request, userId);
} catch (Exception e) {
    log.error("예약 확정 실패 → 보상 트랜잭션 실행");
    compensatePayment(payment);  // 포인트 환불 + 결제 취소
    throw e;
}
```

**왜 Choreography인가?**
- Orchestrator 방식은 별도 조정자 서비스가 필요 → 인프라 복잡
- 현재 구조에서는 PaymentService가 실질적 조정자 역할
- 보상이 필요한 곳이 1곳(결제→예약)뿐이므로 단순 try-catch로 충분

## 3. 서킷브레이커 (Circuit Breaker)

### 상태 전이
```
CLOSED (정상) ──실패율 50% 초과──▶ OPEN (차단)
    ▲                                    │
    │                              30초 경과
    │                                    ▼
    └────── 성공률 회복 ◀──── HALF_OPEN (시험)
```

### 설정값 의미
```java
CircuitBreakerConfig.custom()
    .slidingWindowSize(10)           // 최근 10개 요청 기준
    .failureRateThreshold(50)        // 실패율 50% 넘으면 OPEN
    .waitDurationInOpenState(30s)    // OPEN 후 30초 동안 차단
    .permittedNumberOfCallsInHalfOpenState(3)  // HALF_OPEN에서 3개만 시험
    .slowCallDurationThreshold(2s)   // 2초 이상이면 "느린 호출"
    .slowCallRateThreshold(80)       // 느린 호출 비율 80% 넘으면 OPEN
```

### Redis 장애 시 동작
- Redis 연결 실패 → 예외 → 서킷 열림
- 이후 30초간 Redis 호출 즉시 실패 (대기 없이)
- 30초 후 3개 요청으로 Redis 복구 확인
- 복구되면 서킷 닫힘 → 정상 동작

## 4. Rate Limiting

### 알고리즘: Sliding Window (Redis Sorted Set)
```
시간 ──────────────────────────────────▶
      │  윈도우 (1초)  │
      │ ■ ■ ■ ■ ■ ■ ■ │ ← 7개 (한도: 10)
      │                │
         ZREMRANGEBYSCORE로 윈도우 밖 제거
         ZCARD로 현재 수 확인
         한도 이내면 ZADD, 초과면 거부
```

### 적용 위치
- `@RateLimit(maxRequests = 5, windowSeconds = 1)` → 결제 API
- 사용자 식별: 로그인 사용자는 username, 미로그인은 IP

## 5. Transactional Outbox (`RESERVATION_CONFIRMED`)

**문제**: 예약 row 는 커밋됐는데 Kafka 전송만 실패하면, 다운스트림(알림·연동)이 영원히 모를 수 있다. 반대로 전송만 성공하고 DB 가 롤백되면 "유령 이벤트" 가 된다.

**이 프로젝트의 선택**:

- `ReservationService.confirm()` 안에서 **`KafkaOutboxService.enqueueSeatHoldEvent`** 로 같은 트랜잭션에 outbox INSERT.
- 브로커로의 `send` 는 **`KafkaOutboxPublishScheduler`** 가 비동기로 수행. 성공 시 행 **DELETE**, 실패 시 재시도 후 `FAILED`.

**다른 이벤트**: `HOLD_CREATED`, `PaymentComplete` 등은 여전히 **직접 `KafkaTemplate.send`** — 브로커 장애 시 "DB 는 반영됐는데 이벤트만 없음" 구간이 생길 수 있어, 필요하면 동일 패턴으로 확장할지 트레이드오프를 본다.

---

## 6. 장애 시나리오 정리

| 장애 | 영향 | 대응 |
|------|------|------|
| Redis 다운 | 세션/락/홀드/대기열 불능 | 헬스 `ticketingDatastores` DOWN → 트래픽 제거 검토 |
| Kafka 다운 | 직접 send 경로는 알림 등 지연·유실 가능 | `RESERVATION_CONFIRMED` 는 outbox 적재까지는 DB 에 남음 → 브로커 복구 후 스케줄러가 밀어 넣음 |
| Outbox 반복 실패 | `publish_attempts` 초과 시 `FAILED` | 모니터링·수동 재처리·원인(브로커·페이로드) 조사 |
| DB 다운 | 전체 서비스 불능 | 헬스체크 → ALB에서 제거 |
| 외부 PG 장애 | 카드 결제 불가 | 포인트 결제는 정상 작동 |
| 앱 서버 OOM | 해당 인스턴스 불능 | ALB가 다른 인스턴스로 라우팅 |
