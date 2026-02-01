# Redis/Kafka/세션 구조

## Redis 키 구조 상세

### 대기열 (Queue)

#### `queue:concert:{concertId}` (ZSet)
- **타입**: Sorted Set (ZSet)
- **키 형식**: `queue:concert:1`, `queue:concert:2`, ...
- **멤버**: 대기열 토큰 (UUID 문자열)
- **스코어**: 진입 시각 (밀리초 타임스탬프)
- **용도**: 콘서트별 대기열 관리
- **TTL**: 없음 (수동 정리 또는 토큰 TTL로 관리)

**예시**:
```
ZADD queue:concert:1 "token-1" 1706140800000
ZADD queue:concert:1 "token-2" 1706140801000
ZRANK queue:concert:1 "token-1"  # 0 (첫 번째)
ZCARD queue:concert:1  # 2 (대기인원 수)
```

#### `queue:token:{token}` (String)
- **타입**: String (JSON)
- **키 형식**: `queue:token:c13bb5d9-6b59-467e-80ab-...`
- **값**: `{"userId":"user123","concertId":1,"enteredAt":"2026-01-25T10:00:00+09:00"}`
- **용도**: 토큰별 사용자 정보 저장
- **TTL**: 1800초 (30분)

**데이터 구조**:
```json
{
  "userId": "user123",
  "concertId": 1,
  "enteredAt": "2026-01-25T10:00:00+09:00"
}
```

#### `queue:allowed:{token}` (String)
- **타입**: String (JSON)
- **키 형식**: `queue:allowed:c13bb5d9-6b59-467e-80ab-...`
- **값**: `{"concertId":1,"allowedAt":"2026-01-25T10:00:00+09:00"}`
- **용도**: 입장 허용 상태 저장
- **TTL**: 1800초 (30분)

**데이터 구조**:
```json
{
  "concertId": 1,
  "allowedAt": "2026-01-25T10:00:00+09:00"
}
```

### 홀드 (Hold)

#### `hold:seat:{seatId}` (String)
- **타입**: String
- **키 형식**: `hold:seat:1`, `hold:seat:2`, ...
- **값**: 홀드 토큰 (UUID 문자열)
- **용도**: 좌석별 홀드 토큰 매핑
- **TTL**: 300초 (5분, 홀드 TTL과 동일)

**예시**:
```
SET hold:seat:1 "a1b2c3d4-e5f6-7890-abcd-ef1234567890" EX 300
GET hold:seat:1  # "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
```

#### `hold:token:{holdToken}` (String)
- **타입**: String (JSON)
- **키 형식**: `hold:token:a1b2c3d4-e5f6-7890-abcd-...`
- **값**: 홀드 정보 (JSON)
- **용도**: 홀드 토큰별 상세 정보 저장
- **TTL**: 300초 (5분)

**데이터 구조**:
```json
{
  "holdToken": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "concertId": 1,
  "seatId": 1,
  "userId": "user123",
  "expiresAt": "2026-01-25T10:05:00+09:00"
}
```

#### `hold:expires` (ZSet)
- **타입**: Sorted Set (ZSet)
- **키**: `hold:expires`
- **멤버**: 홀드 정보 JSON (payload)
- **스코어**: 만료 시각 (밀리초 타임스탬프)
- **용도**: 만료 시각 기준 정렬로 스케줄러가 만료 홀드 스캔
- **TTL**: 없음 (멤버별로 관리)

**예시**:
```
ZADD hold:expires 1706141100000 '{"holdToken":"...","seatId":1,...}'
ZRANGEBYSCORE hold:expires 0 1706141100000  # 만료된 홀드 조회
```

**Lua 스크립트로 원자적 연산**:
```lua
-- 홀드 생성
if redis.call('EXISTS', KEYS[1]) == 1 then
    return 0  -- 이미 홀드됨
end
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])  -- hold:seat:{seatId}
redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[2])  -- hold:token:{token}
redis.call('ZADD', KEYS[3], ARGV[4], ARGV[3])  -- hold:expires
return 1
```

### 알림 (Notification)

#### `notify:user:{userId}` (List)
- **타입**: List
- **키 형식**: `notify:user:user123`
- **값**: 알림 항목 JSON 배열
- **용도**: 사용자별 알림 목록 저장
- **최대 크기**: 50개 (LTRIM으로 관리)
- **TTL**: 7일

**데이터 구조**:
```json
[
  {
    "type": "HOLD_EXPIRED",
    "message": "예약이 만료되었습니다. A구역 A-1",
    "createdAt": "2026-01-25T10:00:00+09:00"
  },
  {
    "type": "RESERVATION_CONFIRMED",
    "message": "결제가 완료되었습니다. A구역 A-1",
    "createdAt": "2026-01-25T10:01:00+09:00"
  }
]
```

**Redis 명령어**:
```
LPUSH notify:user:user123 '{"type":"HOLD_EXPIRED",...}'
LTRIM notify:user:user123 0 49  # 최대 50개 유지
LRANGE notify:user:user123 0 -1  # 전체 조회
```

### 접속자 추적

#### `active:users` (ZSet)
- **타입**: Sorted Set (ZSet)
- **키**: `active:users`
- **멤버**: 사용자 ID
- **스코어**: 마지막 활동 시각 (밀리초 타임스탬프)
- **용도**: 실시간 접속자 수 추적
- **윈도우**: 5분 (스코어 기준)
- **TTL**: 1시간

**예시**:
```
ZADD active:users 1706140800000 "user123"
ZCOUNT active:users 1706140500000 1706140800000  # 5분 내 접속자 수
ZREMRANGEBYSCORE active:users 0 1706140500000  # 5분 이전 데이터 삭제
```

### 세션

#### `ticketing:sessions:*` (Hash)
- **타입**: Hash (Spring Session)
- **키 형식**: `ticketing:sessions:abc123def456...`
- **용도**: Spring Session 세션 데이터 저장
- **TTL**: 1800초 (30분)

**Spring Session 설정**:
- 네임스페이스: `ticketing:sessions`
- 직렬화: JSON (GenericJackson2JsonRedisSerializer)
- 만료 시간: 30분

### 분산 락

#### `lock:seat:{seatId}` (String)
- **타입**: String
- **키 형식**: `lock:seat:1`, `lock:seat:2`, ...
- **값**: 락 토큰 (UUID 문자열)
- **용도**: 좌석 단위 분산 락
- **TTL**: 5초 (락 획득 시 설정)

**Lua 스크립트로 원자적 해제**:
```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
```

### 캐시

#### `concert:list:{category}:{query}` (String)
- **타입**: String (JSON)
- **키 형식**: `concert:list:IDOL:null`, `concert:list:ALL:winter`, ...
- **값**: 콘서트 목록 JSON
- **용도**: 콘서트 목록 캐싱
- **TTL**: 300초 (5분)

## Kafka 이벤트 구조

### 토픽
- **이름**: `ticketing.seat-hold-events`
- **파티션**: 기본 설정
- **리플리케이션 팩터**: 1 (로컬 개발)

### 이벤트 타입

#### `HOLD_CREATED`
좌석 홀드 생성 시 발행

**이벤트 데이터**:
```json
{
  "type": "HOLD_CREATED",
  "holdToken": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "concertId": 1,
  "seatId": 1,
  "userId": "user123",
  "expiresAt": "2026-01-25T10:05:00+09:00",
  "createdAt": "2026-01-25T10:00:00+09:00"
}
```

#### `HOLD_CANCELED`
홀드 취소 시 발행

**이벤트 데이터**:
```json
{
  "type": "HOLD_CANCELED",
  "holdToken": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "concertId": 1,
  "seatId": 1,
  "userId": "user123",
  "expiresAt": "2026-01-25T10:05:00+09:00",
  "createdAt": "2026-01-25T10:00:00+09:00"
}
```

#### `HOLD_EXPIRED`
홀드 만료 시 발행 (스케줄러가 발행)

**이벤트 데이터**:
```json
{
  "type": "HOLD_EXPIRED",
  "holdToken": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "concertId": 1,
  "seatId": 1,
  "userId": "user123",
  "expiresAt": "2026-01-25T10:05:00+09:00",
  "createdAt": "2026-01-25T10:00:00+09:00"
}
```

#### `RESERVATION_CONFIRMED`
예약 확정 시 발행

**이벤트 데이터**:
```json
{
  "type": "RESERVATION_CONFIRMED",
  "holdToken": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "concertId": 1,
  "seatId": 1,
  "userId": "user123",
  "expiresAt": "2026-01-25T10:05:00+09:00",
  "createdAt": "2026-01-25T10:00:00+09:00"
}
```

### Consumer Group
- **이름**: `ticketing-notification`
- **역할**: 이벤트 수신 후 알림 저장 및 SSE 전송

## 실시간 알림 (SSE)

### 연결 관리
- **서비스**: `SseNotificationService`
- **저장소**: 인메모리 `ConcurrentHashMap<String, SseEmitter>`
- **키**: 사용자 ID
- **값**: `SseEmitter` 인스턴스

### 연결 생명주기
1. **생성**: `GET /api/notifications/stream` 요청 시
2. **유지**: 30분 타임아웃 또는 명시적 종료까지
3. **종료**: 타임아웃, 에러, 페이지 닫기 시

### 이벤트 전송
- **이벤트 타입**: `notification`
- **데이터 형식**: JSON (`NotificationItemResponse`)
- **전송 방식**: `emitter.send(SseEmitter.event().name("notification").data(item))`

### 연결 실패 처리
- **자동 재연결**: 클라이언트가 3초 후 재연결 시도
- **폴링 백업**: SSE 연결 실패 시 폴링 API로 대체

## 세션 관리 (Redis)

### Spring Session 설정
- **저장소**: Redis
- **네임스페이스**: `ticketing:sessions`
- **만료 시간**: 30분
- **직렬화**: JSON (GenericJackson2JsonRedisSerializer)

### 세션 데이터 구조
```json
{
  "sessionId": "abc123def456...",
  "creationTime": 1706140800000,
  "lastAccessedTime": 1706140800000,
  "maxInactiveInterval": 1800,
  "attributes": {
    "SPRING_SECURITY_CONTEXT": {
      "authentication": {
        "principal": "user123",
        "authorities": []
      }
    }
  }
}
```

### 세션 만료 처리
- **TTL**: Redis 키 TTL로 자동 만료
- **만료 시간**: 30분 (비활성 시간 기준)
- **갱신**: 요청 시마다 `lastAccessedTime` 갱신

## 활성 사용자 추적

### 추적 방식
- **로그인 시**: `ZADD active:users {userId} {timestamp}`
- **로그아웃 시**: `ZREM active:users {userId}`
- **활동 시**: `ZADD active:users {userId} {timestamp}` (갱신)

### 조회 방식
```java
// 5분 내 접속자 수
long now = System.currentTimeMillis();
long windowStart = now - (5 * 60 * 1000);
Long count = redisTemplate.opsForZSet()
    .count("active:users", windowStart, now);
```

### 정리 방식
- **스코어 기준 삭제**: `ZREMRANGEBYSCORE active:users 0 {5분전}`
- **주기**: 조회 시마다 자동 정리

## 대기열 시스템 동작 원리

### 대기열 진입
1. 사용자가 콘서트 선택 시 `POST /api/queue/enter?concertId={id}` 호출
2. 기존 토큰 확인 (중복 진입 방지)
3. 새 토큰 발급 (UUID)
4. Redis ZSet에 토큰 추가: `ZADD queue:concert:{id} {token} {timestamp}`
5. 토큰 정보 저장: `SET queue:token:{token} {data} EX 1800`
6. 순번 및 대기인원 수 반환

### 순번 조회
- **연산**: `ZRANK queue:concert:{id} {token}`
- **복잡도**: O(log N)
- **반환값**: 0부터 시작하는 순번 (화면에는 +1하여 표시)

### 대기인원 수 조회
- **연산**: `ZCARD queue:concert:{id}`
- **복잡도**: O(1)
- **반환값**: 전체 대기인원 수

### 입장 허용 처리
1. 스케줄러가 주기적으로 실행 (기본 2초마다)
2. 각 콘서트별로 상위 N명(배치 크기) 조회: `ZRANGE queue:concert:{id} 0 {batchSize-1}`
3. 입장 허용 상태 설정: `SET queue:allowed:{token} {data} EX 1800`
4. 프론트엔드 폴링에서 입장 허용 감지 후 좌석 선택 화면으로 이동

### 성능 특성
- **대기열 진입**: O(log N) - ZSet 추가
- **순번 조회**: O(log N) - ZSet RANK 연산
- **대기인원 수**: O(1) - ZSet CARD 연산
- **상위 N명 조회**: O(log N + M) - ZSet RANGE 연산 (M은 조회 개수)
- **배치 처리**: 서버 부하 분산

## 홀드 시스템 동작 원리

### 홀드 생성
1. 분산 락 획득 (`lock:seat:{seatId}`)
2. Lua 스크립트로 원자적 연산:
   - 좌석 키 존재 확인 (중복 홀드 방지)
   - 좌석 → 토큰 매핑 저장
   - 토큰 → 홀드 정보 저장
   - 만료 ZSet에 추가
3. Kafka로 `HOLD_CREATED` 이벤트 발행
4. 락 해제

### 홀드 만료 처리
1. 스케줄러가 주기적으로 실행 (기본 60초마다)
2. 만료 ZSet에서 만료된 홀드 조회: `ZRANGEBYSCORE hold:expires 0 {now}`
3. 각 홀드에 대해:
   - Lua 스크립트로 원자적 해제
   - Kafka로 `HOLD_EXPIRED` 이벤트 발행

### 홀드 해제
- **예약 확정 시**: `ReservationService`에서 호출
- **홀드 취소 시**: `HoldService`에서 호출
- **만료 시**: 스케줄러에서 호출
- **Lua 스크립트**: 원자적으로 모든 관련 키 삭제

## Redis 메모리 관리

### 메모리 정책
- **설정**: `maxmemory 400mb`
- **정책**: `allkeys-lru` (모든 키에 대해 LRU 기반 eviction)

### TTL 관리
- **대기열 토큰**: 30분
- **홀드**: 5분
- **세션**: 30분
- **알림**: 7일
- **캐시**: 5분

### 자동 정리
- **TTL 만료**: Redis가 자동으로 키 삭제
- **스케줄러**: 만료된 홀드, 오래된 접속자 데이터 정리
