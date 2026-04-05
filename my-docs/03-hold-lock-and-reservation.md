# 03. 홀드·락·예약 확정 (상세)

좌석 선점(홀드)부터 예약 확정까지, **락이 어디서 걸리고**, **Redis와 DB 트랜잭션 경계**가 어떻게 맞춰져 있는지 정리했다.

---

## 1. 홀드 생성 (HoldService.createHold)

**진입점**: `POST /api/holds` (HoldController)

**순서**:
1. Seat 조회 (concertId 일치, 과거 공연 X, **ConcertStatus != CANCELLED**)
2. **락**: `lock:seat:{seatId}` 로 RedisLockService.tryLock(ttl), 재시도는 설정값만큼
3. 좌석이 이미 RESERVED면 409
4. **HoldStore.createHold()** — Redis Lua 스크립트로 원자 처리  
   - `hold:seat:{seatId}` = holdToken (TTL)  
   - `hold:token:{holdToken}` = HoldInfo JSON (TTL)  
   - `hold:expires` ZSet에 payload 추가 (스코어 = 만료 시각)  
   - 이미 seat 키가 있으면 0 반환 → "Seat already held"
5. `hold:user:{userId}` Set에 holdToken 추가
6. Kafka HOLD_CREATED 발행
7. **finally**에서 unlock(lockKey, lockToken)

**정리**: 홀드는 **Redis에만** 있다. DB seat.status는 건드리지 않는다. 좌석 목록에서 "HELD"는 SeatService가 HoldStore.findHeldSeatIds()로 판단해 응답에 넣는다.

---

## 2. 예약 확정 (ReservationService.confirm) — 결제 완료 시에만

**호출처**: PaymentService.completePayment() 안에서만. **별도 POST /api/reservations 없음.**

**순서**:
1. HoldStore.getHold(holdToken), 만료·userId 검증, **공연 CANCELLED 검사**
2. **락**: `lock:seat:{seatId}` tryLock
3. holdStore.isSeatHeldByToken(seatId, holdToken) 재확인
4. Seat 조회, concert 일치·과거 공연 X·CANCELLED X·이미 RESERVED 아님 확인
5. **DB**: seat.status = RESERVED, save; Reservation 생성(CONFIRMED), save
6. **도메인 이벤트**: `applicationEventPublisher.publishEvent(new ReservationConfirmedEvent(holdToken, hold))`  
   → **아직 커밋 전**이므로 리스너는 실행되지 않는다(AFTER_COMMIT 이므로).
7. **Transactional Outbox**: `kafkaOutboxService.enqueueSeatHoldEvent(...)` 로 **`SeatHoldEvent`(타입 `RESERVATION_CONFIRMED`) JSON 을 `kafka_outbox` 테이블에 INSERT** — 이 INSERT 도 **같은 트랜잭션**에 포함된다.
8. **finally**에서 `lock:seat:{id}` unlock

**트랜잭션 경계 한 줄 요약**:  
- **커밋 성공 시** 함께 영구화되는 것: 예약 row, 좌석 상태, **outbox row**.  
- **커밋 후**에만 할 일: Redis 홀드 키 정리(리스너).  
- **Kafka 브로커로의 실제 send**: 트랜잭션 밖의 **`KafkaOutboxPublishScheduler`** 가 담당한다.

---

## 3. DB 커밋 후 Redis 홀드만 해제 (ReservationConfirmedEventListener)

- **@TransactionalEventListener(phase = AFTER_COMMIT)**
- 이벤트: `ReservationConfirmedEvent(holdToken, holdInfo)`
- 동작: **`holdStore.releaseHold(holdToken)`** 만 호출 (`HoldReleaseMetrics` 기록).

**Kafka `RESERVATION_CONFIRMED` 는 여기서 보내지 않는다.** 리스너 주석대로, 전송은 `KafkaOutboxPublishScheduler` 가 outbox 를 읽어 수행한다.

**왜 releaseHold 는 AFTER_COMMIT 인가?**  
`confirm()` 안에서 Redis 를 먼저 지우면, DB 가 롤백될 때 "예약은 없는데 홀드만 없어짐" 이 될 수 있다. 반대로 커밋이 확정된 뒤에만 홀드를 지우면, Redis 정리 실패가 나도 **DB 상 예약은 이미 유효**하다(홀드 TTL·cleanup 으로 정리 가능). 상세 표는 `docs/sequence-diagrams.md` §5.

---

## 4. 락 (RedisLockService)

- **키**: `lock:seat:{seatId}`
- **값**: UUID 토큰 (unlock 시 본인 락인지 검증)
- **TTL**: ticketing.lock.ttl-seconds (기본 5초)
- **획득**: setIfAbsent(key, token, ttl)
- **해제**: Lua로 GET 한 값이 내 토큰일 때만 DEL

**사용 위치**:
- HoldService.createHold
- ReservationService.confirm  
환불 배치의 cancelReservationForRefund는 **Reservation** row에 PESSIMISTIC_WRITE (findWithLockById)로 동시 취소만 막고, 좌석 락은 사용하지 않는다.

---

## 5. 홀드 취소 (사용자가 "홀드 취소" 버튼)

- **API**: `DELETE /api/holds/{holdToken}` (HoldController)
- **동작**: HoldStore.getHold, userId 일치 확인 후 holdStore.releaseHold(holdToken), Kafka HOLD_CANCELED 발행  
DB 트랜잭션 없고 Redis만 정리한다.

---

더 많은 Redis 키·Outbox·Kafka 경로는 `docs/data.md`, **`my-docs/06-redis-kafka-reference.md`** 참고.
