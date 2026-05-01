# 동시성 / 락

---

### 🟢 Q1. 좌석 동시 선점을 어떻게 막았는지 설명해 주세요.

**A.** 동일 좌석에 여러 사용자가 동시에 홀드/예약을 시도하는 것을 막기 위해 **Redis 분산 락 + Lua 스크립트 원자적 상태 변경 + DB 검증**의 3중 방어를 사용합니다.

`HoldService.createHold()` 흐름:
1. `Seat` 조회·검증 (concertId 일치, CANCELLED/과거공연 아님, DB 상 RESERVED 아님)
2. `lockService.tryLock("lock:seat:" + seatId, ttl)` — Redis 분산 락
3. 락 안에서 다시 `seat.getStatus() == RESERVED` 검증
4. `holdStore.createHold(info, ttl)` — Lua 스크립트로 `hold:seat:{seatId}`·`hold:token:{token}`·`hold:expires` ZSet 원자 갱신 (`EXISTS` 0이면 생성, 1이면 0 반환→409)
5. `finally` 에서 `lockService.unlock(lockKey, lockToken)` (Lua: 본인 토큰일 때만 DEL)

> **🟡 Q1-1. `finally` 에서 unlock 하는 이유? 어차피 TTL 로 풀리지 않나요?**
> **A.** TTL(기본 5초)로 자동 해제되긴 하지만, 정상 흐름에서 5초간 다른 사용자가 같은 좌석을 홀드하지 못하게 됩니다. `finally` 에서 즉시 해제하면 락 점유 시간을 최소화해 다음 요청 처리 속도가 빨라집니다. unlock 도 Lua (`GET → 토큰 비교 → DEL`) 로 자기 토큰일 때만 풀어 다른 락을 실수로 해제하지 않게 했습니다.

---

### 🟡 Q2. 좌석 락을 왜 DB 행 락 대신 Redis 로 구현했나요?

**A.** 두 가지 이유입니다.

첫째, **커넥션 점유 시간 문제**. DB 비관적 락(`SELECT ... FOR UPDATE`)으로 좌석 경합을 막으려면 홀드 생성 시작부터 커밋까지 트랜잭션을 열고 있어야 합니다. 이 구간 동안 DB 커넥션 한 개가 점유되는데, 동시에 수백 명이 같은 좌석을 노리는 상황에서는 커넥션 풀이 먼저 소진됩니다.

Redis 락은 트랜잭션 밖에서 잡고(`setIfAbsent`), 홀드 생성 후 즉시 해제합니다. DB 커넥션 점유 없이 경합을 1차로 걸러냅니다.

둘째, **다중 인스턴스**. DB 행 락은 인스턴스 내부에서 동기화되지 않아 ALB 뒤 2대에서 같은 좌석에 동시에 트랜잭션을 열 수 있습니다. Redis 는 공유 저장소라 인스턴스 수와 무관하게 좌석 단위 직렬화가 됩니다.

> **🟡 Q2-1. 그럼에도 DB 트랜잭션 락이 필요한 부분은?**
> **A.** 있습니다. **돈과 좌석 최종 상태 변경 구간**은 DB 가 마지막 방어선입니다.
> - `paymentRepository.findWithLockByPaymentKey` — 결제 상태 변경 (PESSIMISTIC_WRITE)
> - `paymentRepository.findWithLockByHoldToken` — 같은 holdToken 재요청 차단
> - `usersRepository.findWithLockByUsername` — 포인트 차감/환불
> - `reservationRepository.findWithLockById` — 환불 시 예약 취소
>
> Redis 락은 TTL 기반이라 장애로 예상보다 빨리 풀릴 수 있어, 돈이 움직이는 곳은 명시적 PESSIMISTIC_WRITE 로 직렬화합니다.

---

### 🟡 Q3. Lua 스크립트까지 써서 원자성을 보장한 이유?

**A.** 좌석 홀드 생성은 최소 3개 키를 동시에 갱신해야 합니다. `HoldStore.CREATE_SCRIPT`:
```lua
if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])  -- seat→token
redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[2])  -- token→info
redis.call('ZADD', KEYS[3], ARGV[4], ARGV[3])       -- expires ZSet
return 1
```
명령어 여러 개로 나누면 EXISTS 와 SET 사이에 다른 요청이 끼어들어 부분 반영 상태가 남을 수 있는데, Lua 는 Redis 서버에서 원자적으로 실행되므로 차단됩니다.

> **🟡 Q3-1. RELEASE_SCRIPT 는 어떻게 동작하나요?**
> **A.**
> ```lua
> if redis.call('GET', KEYS[1]) == ARGV[1] then
>     redis.call('DEL', KEYS[1])  -- 본인 토큰일 때만 hold:seat 삭제
> end
> redis.call('DEL', KEYS[2])      -- hold:token 삭제
> redis.call('ZREM', KEYS[3], ARGV[2])  -- hold:expires 멤버 제거
> ```
> seat 키 삭제만 토큰 검증을 하는 이유: cleanup 과 새 홀드 생성이 겹칠 때 새 홀드가 실수로 삭제되지 않게. token/zset 은 어차피 토큰별이라 무조건 삭제 안전.

---

### 🟡 Q4. 락 TTL 을 5초로 설정한 근거와, TTL 이 짧아서 문제되는 경우는?

**A.** `TicketingProperties.Lock.ttlSeconds = 5`, `retryCount = 0` 이 디폴트입니다. 5초 안에 홀드 생성 응답이 오지 않으면 사용자 입장에서도 너무 느린 거고, Redis 에 불필요하게 긴 락을 남기지 않으려는 운영 절충입니다.

> **🟡 Q4-1. 5초 안에 처리가 안 끝나면 데이터가 꼬이지 않나요?**
> **A.** 락 TTL 만료 ≠ 데이터 꼬임입니다. 3중 방어로:
> - `HoldStore.CREATE_SCRIPT` 의 `EXISTS` 가 이미 홀드된 좌석 차단
> - `ReservationService.confirm()` 에서 `seat.getStatus() == RESERVED` 검증
> - `holdStore.isSeatHeldByToken()` 재확인
>
> 즉, 락은 "경쟁 완화" 역할이고, 최종 정합성은 Lua 원자 검증과 DB 트랜잭션이 책임집니다. 부하 테스트에서 TTL 부족이 보이면 `ticketing.lock.ttl-seconds` 환경변수로 조정합니다.

---

### 🔴 Q5. 홀드 만료와 예약 확정이 동시에 일어나는 레이스는 어떻게?

**A.** 세 단계로 방어합니다.
1. `ReservationService.confirm()` 진입 시 `hold.getExpiresAt().isBefore(Instant.now())` → 이미 만료면 즉시 409
2. `lock:seat:{seatId}` 좌석 락으로 `HoldCleanupScheduler` 와 동시에 같은 좌석을 조작하려 하면 한쪽이 실패
3. 락 안에서 `holdStore.isSeatHeldByToken(seatId, holdToken)` 재확인 → cleanup 이 먼저 지웠으면 false → 409
4. DB 트랜잭션에서 `seat.setStatus(RESERVED)` → 동일 좌석 두 번 RESERVED 커밋 불가

> **🔴 Q5-1. `HoldCleanupScheduler` 는 좌석 락을 안 잡고 홀드를 삭제하는데 괜찮나요?**
> **A.** 네. `HoldStore.RELEASE_SCRIPT` 에서 `GET hold:seat:{seatId}` 가 해당 토큰과 일치할 때만 `DEL` 합니다. 예약 확정이 먼저 AFTER_COMMIT 으로 홀드를 해제했다면 cleanup 은 토큰 불일치로 noop. 반대로 cleanup 이 먼저 지웠다면 confirm 이 `isSeatHeldByToken()` false 로 409. 결국 **마지막에 확정한 쪽만 살아남는** 구조입니다.

---

### 🔴 Q6. 이 프로젝트의 "이중 방어" 를 정리해 주세요.

**A.** 좌석 경합은 4개 레이어로 막습니다:

| 레이어 | 역할 | 비용 |
|--------|------|------|
| Redis 락 (`lock:seat:{id}`) | 빠른 경쟁 완화 | ~0.1ms |
| Lua `EXISTS` 검증 | Redis 레벨 원자적 검증 | 수 μs |
| DB `seat.status` 검증 | 비즈니스 진실 검사 | DB 쿼리 1회 |
| DB 트랜잭션 + UNIQUE | 최종 정합성 | 트랜잭션 비용 |

> **🔴 Q6-1. 이중 방어가 성능에 오버헤드를 주지 않나요?**
> **A.** Redis 호출은 합쳐도 1ms 미만. DB 검증은 어차피 예약 생성 시 필수입니다. 오히려 Redis 락이 없으면 모든 경쟁 요청이 DB까지 도달해 커넥션 풀이 빠르게 소진됩니다 — Redis 에서 먼저 걸러주기 때문에 **DB 부하를 줄이는 효과**가 큽니다.

---

### 🔴 Q7. Saga 보상에 `REQUIRES_NEW` 를 쓴 이유는?

**A.** 결제 완료 (`PaymentService.completePayment`) 가 outer `@Transactional` 인 상태에서 `ReservationService.confirm()` 이 실패하면, 보상 코드(포인트 환불 + 결제 CANCELED)가 같은 트랜잭션이면 outer 롤백과 함께 사라집니다.

`PaymentCompensationService.compensateAfterReservationFailure` 를 `@Transactional(propagation = REQUIRES_NEW)` 로 분리해 outer 와 독립 커밋 → outer 가 롤백되어도 보상은 DB 에 남습니다. 이 후 원래 예외를 다시 던져 클라이언트에는 실패 응답.

> **🔴 Q7-1. 멱등은 어떻게 보장하나요?**
> **A.** `compensateAfterReservationFailure` 가 `payment.getStatus() == CANCELED` 면 즉시 return, `APPROVED` 가 아닌 다른 상태도 return — 이미 보상된 결제에 다시 호출돼도 안전합니다. PESSIMISTIC_WRITE 로 동시 보상 호출도 직렬화.
