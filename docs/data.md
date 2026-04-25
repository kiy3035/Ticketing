# Redis / Kafka 데이터 구조

## Redis 키 구조

| 키 패턴 | 타입 | 용도 | TTL |
|---------|------|------|-----|
| `queue:concert:{concertId}` | ZSet | 콘서트별 대기열 (score=진입시각ms) | 스케줄러 정리 |
| `queue:token:{token}` | String(JSON) | 토큰별 사용자/콘서트 정보 | `token-ttl-seconds` |
| `queue:allowed:{token}` | String(JSON) | 입장 허용 상태 | `token-ttl-seconds` |
| `hold:seat:{seatId}` | String | 좌석→홀드 토큰 매핑 | `hold.ttl-seconds` (기본 600s) |
| `hold:token:{holdToken}` | String(JSON) | 홀드 상세 (concertId, seatId, userId, expiresAt) | `hold.ttl-seconds` |
| `hold:expires` | ZSet | 만료 시각 기준 스캔용 (score=만료시각ms) | 스케줄러 정리 |
| `hold:user:{userId}` | Set | 사용자별 보유 홀드 토큰 목록 | 스케줄러 정리 |
| `lock:seat:{seatId}` | String | 좌석 분산 락 (값=UUID 토큰) | `lock.ttl-seconds` (기본 5s) |
| `lock:batch:{batchName}` | String | 배치 스케줄러 분산 락 | 배치별 상이 |
| `notify:user:{userId}` | List | 사용자별 알림 목록 (최대 50개) | 7일 |
| `active:users` | ZSet | 접속자 추적 (score=활동시각ms) | 1시간 |
| `concert:list:*` | String(JSON) | 콘서트 목록 캐시 | 300s |
| `jwt:bl:{jti}` | String | 로그아웃된 Access Token 블랙리스트 | Access 만료까지 |
| `ratelimit:{identifier}` | ZSet | Rate Limit Sliding Window | window+1s |

## Kafka 토픽/이벤트

| 토픽 | 이벤트 타입 | 발행 시점 | 보장 방식 |
|------|------------|----------|----------|
| `ticketing.seat-hold-events` | `HOLD_CREATED` | 좌석 홀드 생성 | KafkaTemplate 직접 send |
| | `HOLD_CANCELED` | 사용자 홀드 취소 | KafkaTemplate 직접 send |
| | `HOLD_EXPIRED` | 스케줄러 만료 정리 | KafkaTemplate 직접 send |
| | `RESERVATION_CONFIRMED` | 예약 확정 | **Transactional Outbox** |
| `ticketing.payment-complete-events` | `PAYMENT_COMPLETED` | 결제 완료 | KafkaTemplate 직접 send |

- **Producer**: `acks=all`, `idempotence=true`, `retries=3`
- **Consumer Group**: `ticketing-notification` (seat-hold), `ticketing-payment-notification` (payment)
- **DLT**: 3회 재시도(1초 간격) 실패 시 `*.DLT` 토픽으로 전송

## Redis 메모리 설정

- `maxmemory 400mb`, `maxmemory-policy allkeys-lru`
- TTL 만료 + 스케줄러 정리 병행
