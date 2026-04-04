# ADR-001: Redis SETNX 기반 분산 락 선택

## 상태
승인됨 (Accepted)

## 컨텍스트
- 다수의 사용자가 동시에 같은 좌석을 선점(홀드)하려고 할 때, 정확히 1명만 성공해야 한다
- 앱 서버가 2대이므로 JVM 내부 락(synchronized, ReentrantLock)으로는 불가능
- DB 락만으로는 Redis 홀드 생성과 DB 상태 변경의 원자성을 보장하기 어렵다

## 결정
**Redis SETNX + TTL + Lua 해제** 패턴을 채택한다.

### 구현 방식
```java
// 락 획득: SETNX (key=lock:seat:{seatId}, value=UUID, TTL)
Boolean success = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);

// 락 해제: Lua 스크립트 (토큰 일치 시에만 DEL)
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
```

## 대안 검토

| 방식 | 장점 | 단점 | 판정 |
|------|------|------|------|
| DB 비관적 락 | 구현 간단, 트랜잭션 보장 | 성능 낮음, DB 부하 | ❌ 좌석 선점에 부적합 |
| Redisson RLock | 재진입, watchdog | 의존성 추가, 복잡도 | ⚠️ 과도한 스펙 |
| **Redis SETNX** | 가볍고 빠름, TTL 자동 해제 | 단일 인스턴스 의존 | ✅ 채택 |
| Redlock | 다중 Redis 인스턴스 합의 | 인프라 복잡, 이슈 논란 | ❌ 인프라 제약 |

## 단일 인스턴스 SETNX로 충분한 이유
1. **인프라**: Redis 1대(t3a.medium). 다중 인스턴스가 아니므로 Redlock이 의미 없다
2. **TTL 안전망**: 앱이 비정상 종료해도 TTL(3~5초)이 자동으로 락을 해제한다
3. **Lua 해제**: 다른 소유자의 락을 실수로 해제하는 문제를 원천 차단한다
4. **부하 테스트 검증**: 100명 동시 홀드 테스트에서 정확히 1명만 성공 확인

## 결과
- 좌석 선점 동시성 제어가 정확하게 동작
- 락 TTL이 짧아(3초) 데드락 위험 없음
- 추후 Redis Sentinel/Cluster 전환 시 Redisson으로 교체 가능
