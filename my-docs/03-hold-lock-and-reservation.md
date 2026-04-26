# 03. 홀드·락·예약 확정 (트랜잭션 경계)

좌석 선점(홀드)부터 예약 확정까지, **락이 어디서 걸리고, Redis와 DB 트랜잭션 경계가 어떻게 맞춰져 있는지** 정리.

---

## 1. 홀드 생성 (`HoldService.createHold`)

**진입점**: `POST /api/holds` (`HoldController`)

**순서**:
1. `Seat` 조회 + 검증 (concertId 일치, `ConcertStatus != CANCELLED`, 과거 공연 아님)
2. **분산 락**: `lock:seat:{seatId}` — `RedisLockService.tryLock(ttl)`, 실패 시 `retryCount`만큼 재시도 후 429
3. DB seat가 이미 `RESERVED`면 409
4. **`HoldStore.createHold()`** — Redis Lua 스크립트로 4개 키 원자 처리:
   - `EXISTS hold:seat:{seatId}` 0이면(없으면) 다음 단계 진행
   - `SET hold:seat:{seatId} = holdToken EX ttl`
   - `SET hold:token:{holdToken} = HoldInfo JSON EX ttl`
   - `ZADD hold:expires score=만료시각ms member=payload`
   - 반환 0 이면 "이미 홀드됨" → 409 (`ticketing_hold_conflict_total` 카운터 증가)
5. `SADD hold:user:{userId} holdToken` (사용자 홀드 인덱스)
6. **Kafka HOLD_CREATED 직접 발행** (`SeatHoldEventPublisher` → `KafkaTemplate.send`) — outbox 미사용
7. `seatService.evictQueueStatusAvailableSeats(concertId)` — 대기열 화면 잔여석 캐시 무효화
8. **finally**에서 unlock(lockKey, lockToken) — Lua: `GET == ARGV[1]` 체크 후 DEL

**메트릭**:
- `ticketing_hold_created_total{status=success}` Counter
- `ticketing_lock_acquire_failures_total{operation=hold}` Counter
- `ticketing_hold_conflict_total{reason=seat_already_held_redis}` Counter

**정리**: 홀드는 **Redis에만** 있다. DB `seat.status`는 변경하지 않는다. 좌석 목록에서 "HELD"는 `SeatService.listSeats()`가 `HoldStore.findHeldSeatIds()`(MGET)로 결정.

---

## 2. 결제 진행 중 홀드 TTL 연장 (`HoldStore.extendHoldTtl`)

**호출처**: `PaymentService.requestPayment()` 진입 시.

**순서**:
1. 현재 payload 조회 → 새 expiresAt 계산 (`now + 20분`)
2. 새 `HoldInfo` 직렬화
3. **3개 키 갱신** (서킷브레이커 통과):
   - `SET hold:seat:{seatId} EX ttl`
   - `SET hold:token:{holdToken} = newPayload EX ttl`
   - `ZREM hold:expires oldPayload` + `ZADD hold:expires score=newExpires member=newPayload`
4. Redis 장애 시 false 반환 → 결제 진행 막음 (잘못된 확정 방지)

---

## 3. 예약 확정 (`ReservationService.confirm`) — 결제 완료 시에만

**호출처**: `PaymentService.completePayment()` 안에서만. **별도 POST /api/reservations 없음.**

**`@Transactional` 안에서의 순서**:
1. `HoldStore.getHold(holdToken)` 조회 — 만료 검사 + 소유자 검증
2. **분산 락**: `lock:seat:{seatId}` `tryLock` — 실패 시 429
3. `HoldStore.isSeatHeldByToken(seatId, holdToken)` 재확인 (락 사이 만료 가능성 차단)
4. `Seat` 조회 + concertId 일치/CANCELLED/과거공연/이미 RESERVED 여부 확인
5. **DB**:
   - `seat.status = RESERVED`, `seatRepository.save(seat)`
   - `Reservation` 생성(`CONFIRMED`), `reservationRepository.save()`
6. **도메인 이벤트**: `applicationEventPublisher.publishEvent(new ReservationConfirmedEvent(holdToken, hold))`
   → AFTER_COMMIT 리스너이므로 **아직 실행되지 않음** (커밋 후 실행)
7. **Transactional Outbox**: `kafkaOutboxService.enqueueSeatHoldEvent(...)` —
   `SeatHoldEvent(RESERVATION_CONFIRMED, ...)` JSON 을 `kafka_outbox` 테이블에 INSERT
   (같은 트랜잭션이므로 예약 row와 함께 커밋/롤백)
8. **finally**에서 `lock:seat:{id}` unlock

**한 줄 요약 — 트랜잭션 경계**:
- **커밋과 함께 영구화**: 예약 row, 좌석 상태, **outbox row**
- **커밋 이후에만**: Redis 홀드 키 정리 (리스너)
- **트랜잭션 밖**: Kafka 브로커로의 실제 send (`KafkaOutboxPublishScheduler`)

---

## 4. DB 커밋 후 Redis 홀드 해제 (`ReservationConfirmedEventListener`)

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onReservationConfirmed(ReservationConfirmedEvent event) {
    holdStore.releaseHold(event.holdToken());
    holdReleaseMetrics.recordReleased("confirmed");
    seatService.evictQueueStatusAvailableSeats(event.holdInfo().getConcertId());
}
```

- **Kafka `RESERVATION_CONFIRMED` 발행은 여기서 하지 않는다.** 발행은 `KafkaOutboxPublishScheduler`가 outbox를 읽어 수행.
- **왜 AFTER_COMMIT 인가?**
  - `confirm()` 안에서 Redis를 먼저 지우면, DB 롤백 시 "예약은 없는데 홀드만 없어짐" 상태 발생 가능.
  - 커밋 확정 후 홀드를 지우면, Redis 정리 실패해도 **DB 상 예약은 이미 유효**. 홀드는 TTL이나 cleanup 스케줄러가 마저 정리.

---

## 5. 락 (`RedisLockService`)

- **키**: `lock:seat:{seatId}` (좌석 락) / `lock:batch:{name}` (배치 락)
- **값**: UUID 토큰 (unlock 시 본인 락인지 검증)
- **TTL**: `ticketing.lock.ttl-seconds` (좌석은 기본 5초, 배치는 스케줄러별 다름)
- **획득**: `setIfAbsent(key, token, ttl)` (Redis SET NX EX)
- **해제**: Lua 스크립트 — `if GET == ARGV[1] then DEL else 0 end`

**사용 위치**:
- `HoldService.createHold` (좌석 락)
- `ReservationService.confirm` (좌석 락)
- 5종 스케줄러 모두 시작 시 배치 락 획득 (다중 인스턴스 시 한 노드만 실행)

환불 배치의 `cancelReservationForRefund`는 `Reservation`에 PESSIMISTIC_WRITE (`findWithLockById`)로 동시 취소만 막고, 좌석 락은 사용하지 않는다.

---

## 6. 홀드 취소 (사용자 직접)

- **API**: `DELETE /api/holds/{holdToken}` (`HoldController`)
- **`HoldService.cancelHold`**:
  - `holdStore.getHold(holdToken)` → userId 일치 확인
  - `holdStore.releaseHold(holdToken)` (Lua release script: seat key·token key·zset member 모두 정리)
  - `HoldReleaseMetrics.recordReleased("cancelled")`
  - `SeatHoldEventPublisher.publish(HOLD_CANCELED)` (직접 Kafka send)
  - `seatService.evictQueueStatusAvailableSeats(concertId)`
- DB 트랜잭션 없고 Redis만 정리.

---

## 7. 동시성 시나리오 — "이미 RESERVED인 좌석" 보호 계층

| 상황 | 어디서 막히나 |
|------|---------------|
| 동시 홀드 시도 (같은 좌석, 같은 시각) | Redis Lua `EXISTS hold:seat` (1명만 통과) |
| 락 획득 후 Redis 키 직전에 다른 요청 끼어듬 | 좌석 단위 분산 락이 한 명만 진입 보장 |
| TTL 만료 후 다른 락 → 원래 락이 unlock 시도 | Lua unlock 스크립트가 토큰 검사로 차단 |
| 락 사이에 홀드가 만료된 상태에서 confirm | `isSeatHeldByToken()` 재확인 → false면 409 |
| 같은 좌석에 대한 이중 RESERVED | DB seat.status 검사 + 좌석 락 |

---

상세 Redis 키·Outbox·Kafka 경로는 [`06-redis-kafka-reference.md`](06-redis-kafka-reference.md) 참고.
