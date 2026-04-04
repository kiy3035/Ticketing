# 동시성 제어 상세 정리

## 이 프로젝트에서 동시성이 필요한 곳

### 1. 좌석 홀드 (Redis 분산 락)
**문제**: 100명이 동시에 같은 좌석을 선점하려고 함
**해결**: Redis SETNX + TTL + Lua 스크립트

```java
// RedisLockService.tryLock()
// SETNX: 키가 없을 때만 SET → 원자적으로 1명만 성공
Boolean success = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
```

**왜 이렇게 했는가?**
- `setIfAbsent` = Redis의 `SET key value NX EX ttl` 명령
- NX(Not eXists): 키가 이미 있으면 실패 → 동시에 여러 스레드가 호출해도 1개만 성공
- EX(Expire): TTL 자동 만료 → 앱이 비정상 종료해도 락이 영원히 안 풀리는 문제 방지

**Lua 해제가 필요한 이유:**
```lua
-- 이 스크립트 없이 단순 DEL을 하면:
-- 1) A가 락 획득 (TTL 5초)
-- 2) A의 처리가 6초 걸림 (TTL 만료)
-- 3) B가 새 락 획득
-- 4) A가 DEL 실행 → B의 락을 삭제해버림! ❌
-- Lua 스크립트로 "내 토큰과 일치할 때만 삭제"
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
```

### 2. 홀드 생성 (Redis Lua 스크립트)
**문제**: 락 안에서도 seat→token, token→info, ZSET 3개를 한번에 업데이트해야 함
**해결**: HoldStore의 CREATE_SCRIPT (Lua 원자적 실행)

```lua
-- 좌석 키가 없을 때만 3개를 한번에 생성
if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])  -- seat→token
redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[2])  -- token→info
redis.call('ZADD', KEYS[3], ARGV[4], ARGV[3])        -- expires ZSET
return 1
```

**왜 Lua인가?**
Redis는 싱글 스레드이므로 Lua 스크립트 실행 중 다른 명령이 끼어들 수 없다.
만약 개별 명령으로 나누면 EXISTS와 SET 사이에 다른 요청이 끼어들 수 있다.

### 3. 결제/포인트 (DB 비관적 락)
**문제**: 동시 결제 시 포인트 이중 차감
**해결**: `@Lock(LockModeType.PESSIMISTIC_WRITE)` → `SELECT ... FOR UPDATE`

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<Users> findWithLockByUsername(String username);
```

**실제 SQL:**
```sql
SELECT * FROM users WHERE username = ? FOR UPDATE
```
- FOR UPDATE: 이 행을 다른 트랜잭션이 읽거나 쓸 수 없게 잠금
- 두 번째 트랜잭션은 첫 번째가 COMMIT/ROLLBACK할 때까지 대기

**데드락 방지 규칙:**
항상 같은 순서로 락을 획득한다:
1. Payment → 2. Users (포인트 차감) → 3. Seat → 4. Reservation

## 테스트로 검증하는 방법

```java
// 100명 동시 홀드 테스트 (SeatHoldConcurrencyTest)
ExecutorService executor = Executors.newFixedThreadPool(100);
CountDownLatch readyLatch = new CountDownLatch(100);  // 모든 스레드 준비 대기
CountDownLatch startLatch = new CountDownLatch(1);     // 동시 출발 신호

// 각 스레드: readyLatch.countDown() → startLatch.await() → 홀드 시도
// 메인: readyLatch.await() → startLatch.countDown()
// 결과: successCount.get() == 1 (정확히 1명만 성공)
```

## 주의사항 / 트러블슈팅

### TTL 설정 주의
- 락 TTL이 비즈니스 로직 소요시간보다 짧으면 위험
- 예: 락 TTL 3초인데 DB 쿼리가 4초 → 락이 풀린 사이 다른 요청이 끼어들 수 있음
- 해결: 락 TTL은 최소 로직 소요시간의 2배 이상으로 설정

### Redis 장애 시
- Redis가 다운되면 모든 락/홀드가 작동 불가
- 서킷브레이커(Resilience4j)로 빠른 실패 전환
- 실무에서는 Redis Sentinel이나 Cluster를 사용해야 함

### 낙관적 락으로 바꿀 수 있는 곳
- 좌석 상태 변경(`AVAILABLE → RESERVED`)은 충돌이 드물므로 `@Version` 가능
- 단, 현재는 Redis 분산 락 안에서 실행되므로 DB 락이 불필요
