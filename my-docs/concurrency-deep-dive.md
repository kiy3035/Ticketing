# 동시성 제어 상세 정리

## 이 프로젝트에서 동시성이 필요한 곳

### 1. 좌석 홀드 (Redis 분산 락)

**문제**: 100명이 동시에 같은 좌석을 선점하려고 함

**해결**: Redis SETNX + TTL + Lua 스크립트

```java
// RedisLockService.tryLock()
String token = UUID.randomUUID().toString();
Boolean success = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
// SET key value NX EX ttl 와 동등
```

**왜 이렇게 했는가**:
- `setIfAbsent` = Redis `SET key value NX EX ttl`
- NX: 키가 이미 있으면 실패 → 동시 호출 시 1개만 성공
- EX: TTL 자동 만료 → 앱 비정상 종료해도 락이 영원히 안 풀리는 사태 방지

**Lua 해제가 필요한 이유**:
```
1) A가 락 획득 (TTL 5초)
2) A의 처리가 6초 걸림 → TTL 만료
3) B가 같은 키로 새 락 획득
4) A가 단순 DEL 실행 → B의 락을 날려버림 ❌

→ Lua: GET 한 값이 내 토큰일 때만 DEL
```

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
```

### 2. 홀드 생성 (Redis Lua 원자성)

**문제**: 락 안에서도 `hold:seat`, `hold:token`, `hold:expires` 3개 키를 한 번에 갱신해야 함

**해결**: `HoldStore.CREATE_SCRIPT`

```lua
if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])  -- seat→token
redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[2])  -- token→info
redis.call('ZADD', KEYS[3], ARGV[4], ARGV[3])       -- expires ZSet
return 1
```

**왜 Lua인가**: Redis는 싱글 스레드라 Lua 스크립트 실행 중 다른 명령이 끼어들 수 없다. 개별 명령으로 분리하면 EXISTS와 SET 사이에 다른 요청이 끼어들 가능성.

### 3. 결제/포인트 (DB 비관적 락)

**문제**: 동시 결제 시 포인트 이중 차감, 동시 환불 시 포인트 이중 가산

**해결**: `@Lock(LockModeType.PESSIMISTIC_WRITE)` → `SELECT ... FOR UPDATE`

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<Users> findWithLockByUsername(String username);
```

**실제 SQL**:
```sql
SELECT * FROM users WHERE username = ? FOR UPDATE
-- 다른 트랜잭션은 이 행에 대해 COMMIT/ROLLBACK 까지 대기
```

**프로젝트 적용 위치**:
- `UsersRepository.findWithLockByUsername` — 포인트 차감/환불
- `PaymentRepository.findWithLockByPaymentKey` — 결제 상태 변경
- `PaymentRepository.findWithLockByHoldToken` — 같은 holdToken 재요청
- `PaymentRepository.findWithLockById` — 환불 배치, Saga 보상
- `ReservationRepository.findWithLockById` — 환불 시 예약 취소

**데드락 방지 — 항상 같은 순서**:
1. Payment → 2. Users (포인트) → 3. Seat → 4. Reservation

### 4. Saga 보상 (REQUIRES_NEW)

**문제**: outer 트랜잭션이 롤백 예정인데 보상 결과는 살려야 함

**해결**: `@Transactional(propagation = REQUIRES_NEW)` 로 별도 트랜잭션 분리
→ 자세한 건 `04-payment-and-refund.md` §2

---

## 테스트로 검증하는 방법

### 1. `RedisLockConcurrencyTest`
- 같은 key로 N개 스레드가 `tryLock` 동시 호출 → 정확히 1개만 성공해야 함

### 2. `SeatHoldConcurrencyTest`
```java
ExecutorService executor = Executors.newFixedThreadPool(100);
CountDownLatch readyLatch = new CountDownLatch(100);  // 모든 스레드 준비
CountDownLatch startLatch = new CountDownLatch(1);    // 동시 출발 신호

for (int i = 0; i < 100; i++) {
    executor.submit(() -> {
        readyLatch.countDown();
        startLatch.await();
        // 홀드 시도
    });
}

readyLatch.await();    // 100개 스레드 준비 완료 대기
startLatch.countDown(); // 일제히 출발
// → assertThat(successCount.get()).isEqualTo(1);
```

### 3. `RedisLockServiceTest`
- 락 획득/해제, TTL 만료, 토큰 불일치 시 unlock 거부 검증

### 4. `IdempotencyServiceTest`, `RateLimitServiceTest`
- Lua 기반 원자성 + 윈도우 동작 검증

---

## 주의사항 / 트러블슈팅

### TTL 설정 주의
- 락 TTL이 비즈니스 로직 소요시간보다 짧으면 위험
- 예: 락 TTL 3초인데 DB 쿼리가 4초 → 락이 풀린 사이 다른 요청이 끼어들 수 있음
- **이 프로젝트의 안전망**: `HoldStore.CREATE_SCRIPT` 의 EXISTS 체크 + DB seat.status 검증 + `isSeatHeldByToken` 재확인 → 락 만료가 곧장 데이터 꼬임으로 이어지지 않음

### Redis 장애 시
- `RedisCircuitBreakerExecutor` 가 fallback 으로 fail-closed (기본 0L/null/false)
- 홀드 생성·대기열 진입 같은 쓰기 경로는 fallback이 false → 사용자에게 "잠시 후 다시 시도" 응답
- 조회성은 fallback이 빈 결과 → UI에서 안전한 빈 화면

### 낙관적 락으로 바꿀 수 있는 곳
- 좌석 상태 변경(`AVAILABLE → RESERVED`)은 충돌이 드물므로 `@Version` 가능
- 단, 현재는 Redis 분산 락 안에서 실행되므로 DB 락이 불필요
- 환불 배치는 `@Lock(PESSIMISTIC_WRITE)` 유지 — 동시 다발 환불 시 명시적 직렬화가 디버깅 쉬움

### Pinning (Virtual Thread)
- `synchronized` 블록 안에서 I/O 호출 시 carrier thread 점유 → VT 효과 사라짐
- 이 프로젝트는 직접 `synchronized` 사용 없음 (모두 Redis 락 또는 DB 비관적 락)
- MySQL Connector/J 9.x, Lettuce, Spring 6.1 모두 내부 `synchronized` → `ReentrantLock` 전환 완료
