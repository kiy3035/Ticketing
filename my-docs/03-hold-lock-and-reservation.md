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
6. **이벤트 발행**: applicationEventPublisher.publishEvent(new ReservationConfirmedEvent(holdToken, hold))  
   → 이 시점에서는 **Redis 홀드 해제·Kafka 발행 안 함**
7. **finally**에서 unlock

**트랜잭션 경계**:  
DB 커밋이 성공한 뒤에만 Redis 홀드 해제와 Kafka 이벤트를 하려고 **ReservationConfirmedEventListener**를 쓴다.

---

## 3. DB 커밋 후 홀드 해제 (ReservationConfirmedEventListener)

- **@TransactionalEventListener(phase = AFTER_COMMIT)**
- 이벤트: ReservationConfirmedEvent(holdToken, holdInfo)
- 동작: holdStore.releaseHold(holdToken), SeatHoldEventPublisher.publish(RESERVATION_CONFIRMED, holdInfo)

**이렇게 하는 이유**: confirm() 안에서 releaseHold()를 부르면, DB 트랜잭션이 롤백돼도 Redis는 이미 바뀌어서 "홀드는 없는데 예약도 없음" 불일치가 생길 수 있다. 커밋 후에만 Redis/Kafka를 건드리면 롤백 시 불일치가 없다.

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

더 많은 Redis 키·Lua 스크립트 설명은 `docs/data.md`, `my-docs/06-redis-kafka-reference.md` 참고.
