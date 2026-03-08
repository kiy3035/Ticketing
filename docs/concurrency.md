# 동시성 및 좌석 락

## 좌석 동시 선점(락)

동일 좌석을 여러 사용자가 동시에 홀드/예약하지 않도록 Redis 기반 분산 락을 사용한다.

### 락 키 형식

- **키**: `lock:seat:{seatId}` (seatId는 좌석 PK)
- **값**: UUID 토큰 (락 해제 시 본인 락인지 검증용)
- **TTL**: `ticketing.lock.ttl-seconds` (기본 5초). 초과 시 Redis가 키를 삭제하여 자동 해제

### 사용 위치

- **홀드 생성** (`HoldService.createHold`): 좌석 선택 후 홀드 저장 전에 락 획득, 처리 후 즉시 해제
- **예약 확정** (`ReservationService.confirm`): 홀드 → 예약 전환 시 해당 좌석 락 획득, 처리 후 즉시 해제

### 설정

| 설정 | 설명 | 기본값 |
|------|------|--------|
| `ticketing.lock.ttl-seconds` | 좌석 락 유지 시간(초) | 5 |
| `ticketing.lock.retry-count` | 락 획득 실패 시 재시도 횟수 (0=재시도 없음) | 0 |
| `ticketing.lock.retry-delay-ms` | 재시도 간 대기 시간(밀리초) | 50 |

### 재시도

락 획득 실패 시 `ticketing.lock.retry-count`(기본 0), `ticketing.lock.retry-delay-ms`(기본 50)로 N회 재시도 후 429를 반환한다. 0이면 재시도 없이 즉시 `429 Too Many Requests`("Seat is busy"). 부하에 따라 재시도 횟수/간격을 설정으로 조정할 수 있다.
