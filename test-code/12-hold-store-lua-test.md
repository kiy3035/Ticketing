# 12. HoldStore Lua 스크립트 원자성 통합 테스트

> "Redis Lua 스크립트로 원자적 좌석 선점"이 이 포트폴리오의 핵심 주장이다.
> `SeatHoldConcurrencyTest` 가 서비스 레벨(100명 → 1명)을 증명한다면,
> 이 테스트는 **Redis 레이어에서 Lua 스크립트의 동작 자체**를 직접 검증한다.

---

## 1. 검증 대상 — CREATE_SCRIPT

```lua
if redis.call('EXISTS', KEYS[1]) == 1 then
    return 0                                    -- 이미 홀드된 좌석 → 즉시 거절
end
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])  -- seat → holdToken
redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[2])  -- token → holdInfo
redis.call('ZADD', KEYS[3], ARGV[4], ARGV[3])        -- 만료 ZSET 기록
return 1
```

### 왜 Lua 스크립트인가?

Lua 스크립트가 없으면:
```
Thread A: EXISTS seat:1 → 0 (없음)
Thread B: EXISTS seat:1 → 0 (없음)  ← A 가 SET 하기 전에 B 도 읽음
Thread A: SET seat:1 → holdToken-A
Thread B: SET seat:1 → holdToken-B  ← 두 사람이 모두 홀드 성공!
```

Lua 스크립트는 Redis 에서 **원자적(Atomic)** 으로 실행된다:
- EXISTS 체크와 SET 사이에 다른 명령이 끼어들 수 없다
- 결과: 두 번째 도전자는 반드시 `return 0` → `createHold()` 가 `false` 반환

---

## 2. 검증한 6가지 시나리오

`HoldStoreIntegrationTest` (Testcontainers Redis 사용)

| # | 시나리오 | 검증 내용 |
|---|----------|----------|
| 1 | **Lua 원자성 핵심** | 같은 seatId 두 번째 홀드 → `false` (EXISTS 체크로 즉시 거절) |
| 2 | **seatId 독립성** | 서로 다른 seatId 는 각각 홀드 가능 |
| 3 | **해제 후 재홀드** | `releaseHold()` 후 같은 좌석 → 다시 홀드 가능 |
| 4 | **getHold 정합성** | 홀드 생성 후 토큰으로 정보 정확히 복원 |
| 5 | **isSeatHeldByToken** | 올바른 토큰: `true` / 다른 토큰: `false` |
| 6 | **없는 토큰 조회** | `Optional.empty()` 반환 |

### 핵심 검증 코드 (시나리오 1)
```java
@Test
@DisplayName("같은 seatId 두 번째 홀드 시도 → Lua 스크립트 EXISTS 체크로 즉시 거절")
void createHold_second_sameSeat_returnsFalse() {
    HoldInfo first  = holdInfo("user1", SEAT_ID_1);
    HoldInfo second = holdInfo("user2", SEAT_ID_1);   // 다른 사람, 같은 좌석

    boolean firstResult  = holdStore.createHold(first, TTL);
    boolean secondResult = holdStore.createHold(second, TTL);

    assertThat(firstResult).isTrue();
    assertThat(secondResult)
        .as("같은 좌석에 두 번째 홀드는 Lua 스크립트가 즉시 거절해야 한다")
        .isFalse();
}
```

---

## 3. 면접 답변 스크립트

### Q. "Lua 스크립트를 왜 썼어요?"

> "Redis 명령을 개별로 호출하면 EXISTS 와 SET 사이에 다른 스레드가 끼어들 수 있어
> 두 사용자가 동시에 '없음'을 확인하고 모두 홀드에 성공하는 race condition 이 발생합니다.
> Lua 스크립트는 Redis 에서 원자적으로 실행되어 이 gap 을 막습니다."

### Q. "그게 실제로 동작하는 거 어떻게 증명해요?"

> "두 레벨로 검증했습니다.
> ① `HoldStoreIntegrationTest` — 같은 seatId 에 두 번째 createHold 가 false 를 반환하는지 Redis 레이어에서 직접 검증
> ② `SeatHoldConcurrencyTest` — 100개 스레드가 동시에 시도해도 정확히 1명만 성공함을 서비스 레벨에서 검증
> 두 테스트가 서로 다른 레이어를 보완하며 원자성을 증명합니다."

### Q. "두 테스트의 차이가 뭔가요?"

> "HoldStore 테스트는 '스크립트 자체가 맞는 값을 반환하는가' (unit/contract),
> Concurrency 테스트는 '실제 멀티스레드 환경에서 정확히 1명만 성공하는가' (end-to-end) 입니다.
> 전자는 빠르고 결정적이며, 후자는 race condition 을 실제로 만들어내서 확인합니다."
