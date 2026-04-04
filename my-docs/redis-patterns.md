# Redis 사용 패턴 정리

## 이 프로젝트에서 Redis가 하는 일 (7가지)

| 용도 | 키 패턴 | 자료구조 | TTL |
|------|---------|----------|-----|
| 세션 | `ticketing:sessions:*` | Hash | 30분 |
| 분산 락 | `lock:seat:{seatId}` | String | 3~5초 |
| 좌석 홀드 (seat→token) | `hold:seat:{seatId}` | String | 5~10분 |
| 좌석 홀드 (token→info) | `hold:token:{token}` | String | 5~10분 |
| 홀드 만료 추적 | `hold:expires` | Sorted Set | 없음 (스케줄러가 정리) |
| 사용자별 홀드 | `hold:user:{userId}` | Set | 없음 (조회 시 정리) |
| 대기열 | `queue:concert:{id}` | Sorted Set | 없음 |
| 대기열 토큰 | `queue:token:{token}` | String | 60~1800초 |
| 대기열 입장 허용 | `queue:allowed:{token}` | String | 60~1800초 |
| 알림 목록 | `notify:user:{userId}` | List | 7일 |
| 접속자 추적 | `active:users` | Sorted Set | 없음 |
| 캐시 | `concertList::*` | String (JSON) | 5분 |
| 멱등성 키 | `idempotency:{key}` | String | 24시간 |
| Rate Limit | `ratelimit:{id}` | Sorted Set | 윈도우+1초 |
| 배치 락 | `lock:batch:*` | String | 60초 |

## Rate Limiter (Sliding Window) 상세

```lua
-- Lua 스크립트로 원자적 실행
local key = KEYS[1]
local window = tonumber(ARGV[1])     -- 윈도우 크기 (초)
local maxRequests = tonumber(ARGV[2]) -- 최대 요청 수
local now = tonumber(ARGV[3])         -- 현재 시각 (ms)
local windowStart = now - window * 1000

-- 1) 윈도우 밖 오래된 항목 제거
redis.call('ZREMRANGEBYSCORE', key, 0, windowStart)
-- 2) 현재 윈도우 내 요청 수 확인
local count = redis.call('ZCARD', key)
-- 3) 한도 이내면 추가, 초과면 거부
if count < maxRequests then
    redis.call('ZADD', key, now, now .. ':' .. math.random(1000000))
    redis.call('EXPIRE', key, window + 1)
    return 1  -- 허용
end
return 0  -- 거부
```

**왜 Sorted Set인가?**
- 각 요청을 timestamp를 score로 저장
- `ZREMRANGEBYSCORE`로 윈도우 밖 항목 O(log N)으로 제거
- `ZCARD`로 현재 윈도우 내 요청 수 O(1)으로 카운트
- 고정 윈도우와 달리 경계 문제 없음 (진짜 "최근 N초" 기준)

## 메모리 절약 팁
- 홀드 TTL을 짧게: 부하테스트 시 5분, 실서비스 시 10분
- 알림 최대 50건으로 trim
- 만료된 ZSET 항목을 스케줄러가 주기적으로 정리
- Redis `maxmemory-policy allkeys-lru` 설정 권장
