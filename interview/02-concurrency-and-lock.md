# 동시성 / 락

---

### Q1. 좌석 동시 선점을 어떻게 막았는지 설명해 주세요.

**A.** 동일 좌석에 여러 사용자가 동시에 홀드나 예약을 시도하는 것을 막기 위해 **Redis 분산 락 + Lua 스크립트 원자적 상태 변경**을 조합했습니다. `HoldService.createHold()`에서는 먼저 `lockService.tryLock("lock:seat:" + seatId, ttl)`로 좌석 단위 락을 잡고, 락 내부에서 DB의 `seat.getStatus() == RESERVED`를 확인한 뒤 `holdStore.createHold(info, ttl)`의 Lua 스크립트로 `hold:seat:{seatId}`·`hold:token:{token}`·`hold:expires` ZSet을 원자적으로 갱신합니다. 마지막으로 `finally` 블록에서 `lockService.unlock(lockKey, lockToken.get())`으로 반드시 락을 해제합니다.

> **Q1-1. `finally`에서 unlock하는 이유가 뭔가요? 어차피 TTL로 풀리지 않나요?**
> **A.** TTL(기본 5초)로 자동 해제되긴 하지만, 정상 흐름에서 5초간 다른 사용자가 해당 좌석을 홀드하지 못하게 됩니다. `finally`에서 즉시 해제하면 락 점유 시간을 최소화해서 다음 사용자의 요청을 빠르게 처리할 수 있습니다. `RedisLockService.unlock()`은 Lua 스크립트(`GET → 토큰 비교 → DEL`)로 구현되어 있어, 자신의 토큰이 아닌 락을 실수로 해제하는 것도 방지합니다.

---

### Q2. 좌석 락을 왜 DB 행 락 대신 Redis로 구현했나요?

**A.** 이 프로젝트의 핵심이 "동시 접속이 매우 많은 상황에서 좌석 선점 제어"이기 때문에, DB 트랜잭션 락에만 의존하면 DB 커넥션과 락 경합이 병목이 됩니다. Redis는 단일 스레드 기반으로 명령을 순차 처리하고 in-memory 특성상 짧은 TTL의 락을 매우 빠르게 처리할 수 있습니다. 또한 `RedisLockService`는 `setIfAbsent(key, token, ttl)` 한 줄로 전역 분산 락을 잡기 때문에, 애플리케이션 인스턴스 수와 무관하게 좌석 단위로 동시 선점을 제어할 수 있습니다.

> **Q2-1. 그럼에도 DB 트랜잭션 락이 필요한 부분은 없나요?**
> **A.** 있습니다. 환불 배치(`RefundForCancelledConcertScheduler`)에서 `PaymentService.refundCompletedPaymentForCancelledConcert()`는 `paymentRepository.findWithLockById(paymentId)`로 Payment 행을 비관적 잠금(PESSIMISTIC_WRITE)으로 조회합니다. `ReservationService.cancelReservationForRefund()`에서도 `reservationRepository.findWithLockById(reservationId)`를 사용합니다. Redis 락은 TTL 기반이라 장애로 예상보다 빨리 풀릴 수 있기 때문에, 돈과 좌석 최종 상태를 변경하는 구간에서는 DB가 마지막 방어선 역할을 합니다.

---

### Q3. Lua 스크립트까지 써서 원자성을 보장한 이유는 무엇인가요?

**A.** 좌석 홀드 생성은 최소 3개의 Redis 키를 동시에 갱신해야 합니다. `HoldStore.CREATE_SCRIPT`는 먼저 `EXISTS KEYS[1]`(= `hold:seat:{seatId}`)로 이미 홀드된 좌석인지 확인하고, 아니면 `SET KEYS[1]`(좌석→토큰), `SET KEYS[2]`(토큰→홀드 정보 JSON), `ZADD KEYS[3]`(`hold:expires` ZSet에 만료 시각)을 한 번에 수행합니다. 이걸 명령어 여러 개로 나누면 중간에 다른 요청이 끼어들어 부분만 반영된 상태가 남을 수 있는데, Lua 스크립트는 Redis 서버에서 원자적으로 실행되므로 이런 문제가 발생하지 않습니다.

> **Q3-1. RELEASE_SCRIPT는 어떻게 동작하나요?**
> **A.** `RELEASE_SCRIPT`는 먼저 `GET KEYS[1]`(= `hold:seat:{seatId}`)이 해당 홀드 토큰과 일치하는지 확인한 후 `DEL`합니다. 토큰이 불일치하면 다른 사용자의 홀드를 삭제하지 않습니다. 이어서 `DEL KEYS[2]`(토큰 키)와 `ZREM KEYS[3]`(만료 ZSet에서 payload 제거)를 수행합니다. 이 검증 로직이 없으면, 만료된 홀드의 cleanup과 새 홀드 생성이 겹칠 때 새 홀드가 삭제되는 사고가 날 수 있습니다.

---

### Q4. 락 TTL을 5초로 설정한 근거와, TTL이 짧아서 문제되는 경우는 없나요?

**A.** `TicketingProperties.Lock`에서 기본 TTL 5초, 재시도 횟수 0으로 시작했습니다. 사용자 입장에서 5초 이내에 홀드 생성 응답이 오지 않으면 이미 느끼기엔 너무 오래 걸리는 것이고, Redis에 불필요하게 긴 락을 남기지 않으려는 운영 관점의 절충입니다. 재시도 없음(retryCount=0)으로 설정한 이유는, 락 경합이 심한 구간에서 무의미한 재시도가 Redis/스레드 부하를 늘리기 때문입니다.

> **Q4-1. 5초 안에 처리가 안 끝나면 데이터가 꼬이지 않나요?**
> **A.** 락 TTL이 만료되더라도 곧바로 데이터가 꼬이진 않습니다. `HoldStore.CREATE_SCRIPT`의 `EXISTS` 검증이 이미 홀드된 좌석을 차단하고, `ReservationService.confirm()`에서도 `seat.getStatus() == RESERVED` 검증과 `holdStore.isSeatHeldByToken()` 확인을 합니다. 즉, 락은 "경쟁 완화" 역할이고, 최종 정합성은 Lua 스크립트의 원자적 검증과 DB 트랜잭션이 책임집니다. 부하 테스트에서 특정 구간의 TTL이 부족하다고 판단되면 `ticketing.lock.ttl-seconds`를 환경 변수로 조정합니다.

---

### Q5. 홀드 만료와 예약 확정이 동시에 일어나는 레이스 컨디션은 어떻게 처리했나요?

**A.** 세 단계로 방어합니다. 첫째, `ReservationService.confirm()`에서 `hold.getExpiresAt().isBefore(Instant.now())`로 이미 만료된 홀드이면 바로 실패 처리합니다. 둘째, 좌석 단위 락(`lock:seat:{seatId}`)을 잡기 때문에 `HoldCleanupScheduler`와 동시에 같은 좌석을 조작하려 하면 락 경합에서 한쪽이 실패합니다. 셋째, DB 트랜잭션 안에서 `seat.setStatus(RESERVED)`를 하므로 동일 좌석에 대해 두 개의 예약이 CONFIRMED 상태로 커밋되는 것은 불가능합니다.

> **Q5-1. `HoldCleanupScheduler`는 좌석 락을 안 잡고 홀드를 삭제하는 것 같은데, 괜찮나요?**
> **A.** 네, `HoldCleanupScheduler`는 `holdStore.releaseByPayload()`를 호출하는데, `RELEASE_SCRIPT`에서 `GET hold:seat:{seatId}`가 해당 토큰과 일치할 때만 `DEL`합니다. 만약 예약 확정 쪽에서 이미 `AFTER_COMMIT` 리스너로 홀드를 해제했다면 seat 키가 없거나 토큰이 불일치하므로 cleanup이 아무 동작을 하지 않습니다. 반대로 cleanup이 먼저 홀드를 지웠다면, 예약 확정 쪽에서 `holdStore.isSeatHeldByToken()`이 false를 반환해 CONFLICT를 던집니다.

---

### Q6. 이 프로젝트에서 "이중 방어(Redis 락 + DB 트랜잭션)"라고 할 수 있는 부분을 정리해 주세요.

**A.** 홀드 생성 시 `HoldService.createHold()`에서 Redis 분산 락 → DB `SeatStatus.RESERVED` 검증 → Lua `EXISTS` 검증이 3중으로 동시 선점을 차단합니다. 예약 확정 시 `ReservationService.confirm()`에서 Redis 분산 락 → `isSeatHeldByToken()` 검증 → DB에서 `seat.getStatus() == RESERVED` 확인 후 `seat.setStatus(RESERVED)` + `reservationRepository.save()`로 트랜잭션 커밋합니다. 이 구조에서 Redis 락은 "빠른 경쟁 완화", Lua는 "Redis 레벨 원자적 검증", DB 트랜잭션은 "최종 정합성 보장"이라는 세 가지 역할을 각각 담당합니다.

> **Q6-1. 이중 방어가 성능에 오버헤드를 주지 않나요?**
> **A.** Redis 락 획득은 `setIfAbsent` 한 번(~0.1ms), Lua 스크립트도 Redis 내부에서 수 마이크로초 이내에 끝납니다. DB 트랜잭션은 어차피 예약 생성 시 필수이므로 추가 비용이 아닙니다. 오히려 Redis 락이 없으면 모든 경쟁 요청이 DB까지 도달해 커넥션 풀이 빠르게 소진되는데, Redis에서 먼저 걸러주기 때문에 DB 부하를 줄이는 효과가 있습니다.
