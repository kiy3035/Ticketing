# Redis / Kafka 데이터 구조

## Redis 키 구조

| 키 패턴 | 타입 | 용도 | TTL |
|---------|------|------|-----|
| `queue:concert:{concertId}` | ZSet | 콘서트별 대기열 (멤버=토큰, 스코어=진입시각ms) | 없음 (스케줄러 정리) |
| `queue:token:{token}` | String(JSON) | 토큰별 사용자/콘서트 정보 | `token-ttl-seconds` (기본 1800초) |
| `queue:allowed:{token}` | String(JSON) | 입장 허용 상태 | `token-ttl-seconds` |
| `hold:seat:{seatId}` | String | 좌석→홀드 토큰 매핑 | `hold.ttl-seconds` (기본 600초) |
| `hold:token:{holdToken}` | String(JSON) | 홀드 상세 정보 (concertId, seatId, userId, expiresAt) | `hold.ttl-seconds` |
| `hold:expires` | ZSet | 만료 시각 기준 홀드 스캔용 (멤버=payload, 스코어=만료시각ms) | 없음 |
| `lock:seat:{seatId}` | String | 좌석 분산 락 (값=UUID 토큰) | `lock.ttl-seconds` (기본 5초) |
| `notify:user:{userId}` | List | 사용자별 알림 목록 (LPUSH, LTRIM 50개) | 7일 |
| `active:users` | ZSet | 접속자 추적 (멤버=userId, 스코어=활동시각ms) | 1시간 |
| `ticketing:sessions:*` | Hash | Spring Session 데이터 | 1800초 (30분) |
| `concert:list:{category}:{query}` | String(JSON) | 콘서트 목록 캐시 | 300초 (5분) |

### 홀드 Lua 스크립트

홀드 생성/해제 시 다중 키를 원자적으로 처리하는 Lua 스크립트를 사용한다.
- **생성**: EXISTS 확인 → `hold:seat`, `hold:token` SET → `hold:expires` ZADD (1 트랜잭션)
- **해제**: `hold:seat`, `hold:token` DEL → `hold:expires` ZREM (1 트랜잭션)

## Kafka 토픽/이벤트

| 토픽 | 이벤트 타입 | 발행 시점 |
|------|------------|----------|
| `ticketing.seat-hold-events` | `HOLD_CREATED` | 좌석 홀드 생성 |
| | `HOLD_CANCELED` | 사용자 홀드 취소 |
| | `HOLD_EXPIRED` | 스케줄러 만료 홀드 정리 |
| | `RESERVATION_CONFIRMED` | 예약 DB 커밋과 동일 트랜잭션에 outbox 적재 → 스케줄러가 Kafka 발행 |
| `ticketing.payment-complete-events` | `PAYMENT_COMPLETED` | 결제 완료 → 이메일/SMS 비동기 알림 |

- **Consumer Group**: `ticketing-notification` — 이벤트 수신 후 Redis 알림 저장 + SSE 전송
- **Producer 설정**: `acks=all`, `idempotence=true`, `retries=3`

## 세션

- **저장소**: Redis (`spring-session-data-redis`)
- **네임스페이스**: `ticketing:sessions`
- **직렬화**: JSON (`GenericJackson2JsonRedisSerializer`)
- **만료**: 30분 비활성 시 자동 만료

## Redis 메모리 정책

- `maxmemory 400mb`, `allkeys-lru`
- TTL 만료 + 스케줄러 정리로 메모리 관리
