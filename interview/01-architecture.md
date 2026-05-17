# 아키텍처 / 설계

---

### 🟢 Q1. 이 프로젝트가 어떤 서비스인지 한 줄로 설명해주세요.

**A.** 콘서트 좌석 예매 백엔드입니다. 오픈 시점 트래픽 폭증에 대비해 **대기열·좌석 동시 선점·결제 정합성**을 다루는 게 핵심입니다. Spring Boot 3.4 + Java 21 + MySQL/Redis/Kafka 로 구성했고, EC2 위 Docker 로 운영합니다.

---

### 🟢 Q2. 전체 아키텍처를 간단히 설명해 주세요.

**A.** 클라이언트 → nginx → Spring Boot API → MySQL/Redis/Kafka 로 나뉜 **레이어드 + 이벤트 주변부** 구조입니다.
- **레이어**: `Controller → Service → (JPA)Repository` + Redis 전용 `HoldStore`, `RedisLockService`
- **인증**: Spring Security + **JWT (Access/Refresh)** — 세션 미사용
- **비동기**: `SeatHoldEventPublisher` / `PaymentCompleteEventPublisher` + Kafka Consumer
- **배치**: 5종 스케줄러 (`Queue Process/Cleanup`, `Hold Cleanup`, `Refund`, `Kafka Outbox Publish`)
- **저장소 역할 분담**: MySQL = 감사 가능한 진실 (예약·결제·좌석), Redis = 고속·휘발성 (대기열·홀드·락·캐시·블랙리스트)

> **🟢 Q2-1. Store 레이어를 둔 이유는요?**
> **A.** `HoldStore` 처럼 Lua·ZSet·여러 키를 다루는 코드가 서비스에 풀려있으면 비즈니스 규칙과 Redis 명령이 뒤섞입니다. 서비스는 `holdStore.createHold(info, ttl)` 같은 도메인 언어만 쓰고, 키 패턴·스크립트는 Store 에 캡슐화했습니다.

> **🟡 Q2-2. Controller 가 Repository 를 직접 안 쓰나요?**
> **A.** ArchUnit 으로 강제했습니다 (`ArchitectureTest`). 예: 대기열에서 "남은 좌석 수"는 `QueueController` → `SeatService.countAvailableSeatsForQueueStatus()` 로만 계산하고, JPA Repository 는 서비스 이하에만. 도메인 패키지가 Spring 에 의존하지 않게 하는 규칙도 있습니다.

---

### 🟡 Q3. Kafka 를 왜 썼고, 동기 호출과 뭐가 다른가요?

**A.** 결제 완료 후 이메일·SMS 는 **응답 시간과 분리**해야 해서 `PaymentCompleteEvent` 를 Kafka 로 보내고 컨슈머에서 처리합니다. 홀드 관련 이벤트(`HOLD_CREATED`, `HOLD_CANCELED`, `HOLD_EXPIRED`)도 동일하게 비동기로 다운스트림이 받아 SSE/알림 처리합니다. **`RESERVATION_CONFIRMED` 만** 예약 **DB 커밋과 같은 트랜잭션** 에 `kafka_outbox` 행으로 적재하고, `KafkaOutboxPublishScheduler` 가 주기(500ms) 로 Kafka 로 밀어 넣습니다.

> **🟡 Q3-1. Kafka 가 죽으면 결제가 실패하나요?**
> **A.** 결제·예약 **커밋은 Kafka 와 무관**합니다.
> - `PaymentComplete` 직접 send 가 실패해도 DB는 이미 반영. 알림만 지연·유실 가능 (프로듀서 `acks=all`, `retries=3`, `enable.idempotence=true` 로 완화).
> - `RESERVATION_CONFIRMED` 는 outbox 가 남으므로 브로커 복구 후 스케줄러가 다시 보냅니다.

> **🔴 Q3-1-1. `acks=all`·`retries=3`·`enable.idempotence=true` 세 옵션이 각각 뭘 보호하나요?**
> **A.** 세 옵션이 같이 작동해야 "유실 0 + 중복 0" 에 가까운 전송 보장이 됩니다.
> - **`acks=all`** — 메시지 유실 방지. leader + 모든 in-sync replica 가 받아야 send 성공으로 처리. leader 브로커가 죽어도 replica 에 복제됐기 때문에 살아남습니다.
> - **`retries=3`** — 일시적 네트워크 실패 시 프로듀서가 자동 재시도. 1초 간격 고정 백오프.
> - **`enable.idempotence=true`** — `acks=all` 재시도 도중 사실은 브로커가 이미 받았던 경우, 같은 메시지가 두 번 저장되는 것 방지. 프로듀서 PID + 시퀀스 번호로 브로커가 중복 감지·제거합니다. 설정 위치는 `application.properties:53-55`.

> **🟡 Q3-1-2. Kafka 는 at-least-once 인데 컨슈머 중복 수신은 어떻게 막나요?**
> **A.** 프로듀서 멱등성과 별개로 컨슈머에서도 멱등성 가드를 둡니다. `PaymentCompleteEventConsumer` 에서 `paymentKey` 를 멱등성 키(`kafka:payment-complete:{paymentKey}`)로 잡고 `IdempotencyService.acquireKey()` (Redis SETNX 기반)로 한 번만 통과시킵니다. TTL 24시간. 같은 `paymentKey` 가 리밸런스·재시도로 또 와도 `acquireKey` 가 `false` 를 반환해 알림 재발송 스킵. 처리 실패 시 `releaseKey` 로 풀어 Kafka 재시도가 다시 발송할 수 있게 합니다.

> **🟡 Q3-1-3. 알림 발송이 계속 실패하면 어떻게 처리되나요?**
> **A.** `KafkaConfig.createErrorHandler()` 에서 `DefaultErrorHandler + FixedBackOff(1000ms, 3회)` 로 3회 재시도 후 실패하면 Dead Letter Topic(원래 토픽 + `.DLT`, 예: `ticketing.payment-complete.DLT`) 으로 보냅니다. DLT 는 운영자가 수동 모니터링해서 재처리하는 큐. 컨슈머 코드가 예외를 잡지 않고 그대로 throw 하기 때문에 이 에러 핸들러가 정상 동작합니다.

> **🔴 Q3-2. 왜 모든 이벤트를 outbox로 보내지 않았나요?**
> **A.** outbox 는 추가 INSERT + 스케줄러 + 모니터링 비용을 동반합니다. **DB 커밋과 반드시 묶여야 하는 발행** 만 outbox 로 보내고 (=`RESERVATION_CONFIRMED`), 나머지는 직접 send 로 두는 트레이드오프입니다. 운영하면서 `HOLD_*` 알림 누락이 비즈니스에 치명적이라고 판단되면 같은 패턴으로 확장 가능하다는 걸 코드 구조상 보장해 두었습니다.

---

### 🔴 Q4. `ReservationConfirmedEventListener` 의 `AFTER_COMMIT` 은 왜 쓰나요? Kafka 도 여기서 내나요?

**A.** `ReservationService.confirm()` 안에서 좌석 `RESERVED`·예약 INSERT·**outbox INSERT** 까지 한 트랜잭션으로 묶입니다. **`publishEvent(ReservationConfirmedEvent)`** 는 리스너를 **커밋 이후**에만 돌리기 위해 쓰고, 리스너는 **`holdStore.releaseHold()` + 잔여석 캐시 evict** 만 수행합니다. 트랜잭션 안에서 Redis 를 풀면 롤백 시 "DB 는 없는데 홀드만 풀린" 상태가 될 수 있습니다.

**`RESERVATION_CONFIRMED` Kafka 발행은 리스너가 아니라 outbox 스케줄러**가 담당합니다. 이렇게 분리한 이유는 (a) 발행이 실패해도 DB 트랜잭션이 영향받지 않게 (b) 재시도/FAILED 추적이 가능하게 하려고요.

> **🔴 Q4-1. AFTER_COMMIT 에서 Redis 해제만 실패하면?**
> **A.** DB 예약은 이미 확정입니다. 홀드 키가 잠시 남을 수 있으나 좌석은 DB 상 판매 완료라 **이중 판매로 이어지지 않습니다** (홀드 생성 시 `seat.status = RESERVED` 검증). TTL과 `HoldCleanupScheduler`가 마저 정리합니다.

---

### 🟡 Q5. MySQL 과 Redis 를 같이 쓸 때 역할 분리 기준은?

**A.** **감사·정산·법적 추적**이 필요하면 MySQL. **순위·선점·캐시·휘발성**이 핵심이면 Redis. 두 저장소가 엮이는 지점(예약 확정)은 **DB 커밋 후 Redis 정리(AFTER_COMMIT)** 와 **outbox 로 Kafka** 로 경계를 나눕니다.

> **🟡 Q5-1. Redis 장애 시?**
> **A.** 대기열·홀드·락이 막히므로 **오픈 예매 플로우는 사실상 중단**에 가깝습니다. `RedisCircuitBreakerExecutor` 가 OPEN 상태에서 fast-fail 로 응답 시간만큼은 보호하고, `ticketingDatastores` 헬스가 DOWN 으로 떨어집니다(현재 nginx는 passive health check라 헬스 DOWN을 자동 감지하진 않고, upstream 실제 실패 누적으로 격리). 이미 적재된 예약·결제 조회는 DB 로 가능. 다음 단계로는 Sentinel/Cluster 도입 가능.

---

### 🟡 Q6. 스케일아웃을 어떻게 고려했나요?

**A.** 앱은 **상태를 Redis·DB·Kafka** 에 두어 수평 확장 가능합니다.
- **세션 없음** (JWT) → 스티키 불필요
- **스케줄러·outbox 발행**: `lock:batch:*` Redis 분산 락 으로 멀티 인스턴스 중 한 노드만 실행
- **Kafka 컨슈머**: 동일 group-id 로 파티션 단위 분산
- **DB 커넥션**: HikariCP `maximum-pool-size=30`, `minimum-idle=5` — 인스턴스 수 × max-pool 합이 RDS `max_connections` 한계 안쪽이 되도록 설계

현재 t3a.small 앱서버 2대 + 인프라서버 nginx(`least_conn` + passive health check) 구성으로 운영 중. 부하 테스트(Phase 4·5·6·7·8)로 스케일아웃·페일오버 검증 완료.

> **🟡 Q6-1. SSE 는 다중 인스턴스에서 어떻게?**
> **A.** **Redis Pub/Sub 브로드캐스트** 로 해결했습니다. `SseNotificationService` 가 `MessageListener` 를 구현하고, `SseRedisConfig` 가 `RedisMessageListenerContainer` 로 `sse:notify:*` 패턴을 PSUBSCRIBE 합니다. 알림 발행 시 `redisTemplate.convertAndSend("sse:notify:{userId}", json)` → 모든 인스턴스의 `onMessage()` 가 호출되고, **에미터를 보유한 인스턴스만** 자기 emitter 에 send 합니다. Kafka 컨슈머가 어느 인스턴스에서 실행돼도 사용자가 연결된 인스턴스로 알림이 전달됩니다. `SseNotificationMultiInstanceIntegrationTest` (Testcontainers Redis) 에서 cross-instance broadcast·격리·no-op 4 시나리오로 검증.

---

### 🔴 Q7. 지금 구조에서 아쉬운 점·개선하고 싶은 점은?

**A.** 코드 레벨에서 다음을 인지하고 있습니다.
1. **직접 Kafka send 경로**(`HOLD_CREATED`, `PaymentComplete`) 의 운영 재처리·모니터링 — outbox 수준으로 끌어올릴지 트레이드오프
2. **`QueueProcessingScheduler` 가 `findAll()` 로 전 공연 순회** — 대기열 활성 공연만 추리는 인덱싱 필요
3. **`HoldStore.extendHoldTtl` 가 Lua 가 아닌 다중 명령** — 결제 단계라 경합은 적지만 원자성 보강 가능
4. **`QueueService.removeExistingTokens` 가 ZSet 전체 스캔** — `queue:user:{concertId}:{userId}` 역인덱스로 O(1) 화 가능

> **🔴 Q7-1. 처음부터 다시 짠다면?**
> **A.** **읽기 전용 트래픽(공연 목록·좌석 조회)** 을 별도 서비스로 분리해 스케일 단위를 나누는 걸 검토합니다. 현재 단일 모듈 구조는 포트폴리오·온보딩 비용 면에서는 유리한 선택이었습니다.
