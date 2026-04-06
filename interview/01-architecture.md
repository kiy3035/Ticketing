# 아키텍처 / 설계

---

### Q1. 이 프로젝트의 전체 아키텍처를 간단히 설명해 주세요.

**A.** 클라이언트 → Spring Boot API → MySQL / Redis / Kafka 로 나뉜 **레이어드 + 이벤트 주변부** 구조입니다. 내부는 `Controller → Service → (JPA)Repository` 와 Redis 전용 `HoldStore`, `RedisLockService` 로 분리하고, Spring Security + **Spring Session Redis** 로 인증·세션을 다중 인스턴스에 맞췄습니다. 비동기는 `SeatHoldEventPublisher` / `PaymentCompleteEventPublisher` 와 컨슈머, 그리고 `QueueProcessingScheduler`, `HoldCleanupScheduler`, `RefundForCancelledConcertScheduler`, **`KafkaOutboxPublishScheduler`** 등 스케줄러로 나뉩니다. MySQL은 예약·결제·좌석 등 **감사 가능한 진실**, Redis는 대기열·홀드·락·세션 등 **고속·휘발성** 데이터를 담당합니다.

> **Q1-1. Store 레이어를 둔 이유는요?**
> **A.** `HoldStore` 처럼 Lua·ZSET·여러 키를 다루는 코드를 서비스에 풀어두면 비즈니스 규칙과 Redis 명령이 뒤섞입니다. 서비스는 `holdStore.createHold(info, ttl)` 같은 도메인 언어만 쓰고, 키 패턴·스크립트는 Store 에 캡슐화했습니다.

> **Q1-2. Controller 가 Repository 를 직접 안 쓰나요?**
> **A.** 레이어 규칙을 **ArchUnit** 테스트로 고정해 두었습니다. 예를 들어 대기열에서 “남은 좌석 수”는 `QueueController` → `SeatService.countAvailableSeats()` 로만 계산하고, JPA Repository 는 서비스 이하에 둡니다.

---

### Q2. Kafka 를 왜 썼고, 동기 호출과 뭐가 다른가요?

**A.** 결제 완료 후 이메일·SMS 는 **응답 시간과 분리**해야 해서 `PaymentCompleteEvent` 를 Kafka 로 보내고, 컨슈머에서 알림을 처리합니다. 홀드 관련 이벤트(`HOLD_CREATED` 등)도 동일하게 비동기 확장·DLT 연계가 쉽습니다. **`RESERVATION_CONFIRMED` 만** 예약 **DB 커밋과 같은 트랜잭션**에 `kafka_outbox` 행으로 적재하고, `KafkaOutboxPublishScheduler` 가 주기적으로 Kafka 로 밀어 넣습니다. 직접 `send` 만 하면 “DB 는 커밋됐는데 브로커 장애로 메시지가 영원히 안 나가는” 구간이 생기기 쉬운데, outbox 는 **재시도·FAILED 표기**까지 같은 테이블로 추적할 수 있습니다.

> **Q2-1. Kafka 가 죽으면 결제가 실패하나요?**
> **A.** 결제·예약 **커밋은 Kafka 와 무관**합니다. `PaymentComplete` 직접 send 가 실패해도 DB 는 이미 반영된 상태일 수 있고, 그때는 알림만 지연·유실 가능 구간이 됩니다(프로듀서 `retries`, `acks=all` 로 완화). `RESERVATION_CONFIRMED` 는 outbox 가 남으므로 브로커가 살아나면 스케줄러가 다시 보냅니다.

---

### Q3. `ReservationConfirmedEventListener` 의 `AFTER_COMMIT` 은 왜 쓰나요? Kafka 도 여기서내나요?

**A.** `ReservationService.confirm()` 안에서 좌석 `RESERVED`·예약·**outbox INSERT** 까지 한 트랜잭션으로 묶입니다. **`publishEvent(ReservationConfirmedEvent)`** 는 리스너를 **커밋 이후**에만 돌리기 위해 쓰고, 리스너는 **`holdStore.releaseHold()`** 만 수행합니다. 예전처럼 트랜잭션 안에서 Redis 를 풀면 롤백 시 “DB 는 없는데 홀드만 풀린” 상태가 될 수 있습니다. **`RESERVATION_CONFIRMED` Kafka 발행은 리스너가 아니라 outbox 스케줄러**가 담당합니다.

> **Q3-1. AFTER_COMMIT 에서 Redis 해제만 실패하면?**
> **A.** DB 예약은 이미 확정입니다. 홀드 키가 잠시 남을 수 있으나 좌석은 DB 상 판매 완료라 이중 판매로 이어지지 않습니다. TTL·`HoldCleanupScheduler` 로 정리됩니다. 상세 표는 [docs/sequence-diagrams §5](../docs/sequence-diagrams.md#consistency-failure-scenarios) 참고.

---

### Q4. MySQL 과 Redis 를 같이 쓸 때 역할 분리 기준은?

**A.** **감사·정산·법적 추적**이 필요하면 MySQL. **순위·선점·세션**처럼 지연·TTL 이 핵심이면 Redis. 두 저장소가 엮이는 지점(예약 확정)은 **DB 커밋 후 Redis 정리(AFTER_COMMIT)** 와 **outbox 로 Kafka** 로 경계를 나눕니다.

> **Q4-1. Redis 장애 시?**
> **A.** 대기열·홀드·락이 막히므로 **오픈 예매 플로우는 사실상 중단**에 가깝습니다. 이미 적재된 예약·결제 조회는 DB 로 가능합니다. 다운스트림으로는 Sentinel/Cluster·캐시 우회 읽기 전략을 논의할 수 있습니다.

---

### Q5. 스케일아웃을 어떻게 고려했나요?

**A.** 앱은 **상태를 Redis·DB·Kafka**에 두어 수평 확장 가능하게 했습니다. 스케줄러·outbox 발행은 **`lock:batch:*` 분산 락**으로 멀티 인스턴스에서 단일 실행을 보장합니다. Kafka 컨슈머는 **동일 group-id** 로 파티션 단위 분산. 배포·ALB·JWT·SSE 등은 [docs/deployment-ec2.md](../docs/deployment-ec2.md) 와 동일 맥락입니다.

> **Q5-1. SSE 는?**
> **A.** `SseNotificationService` 가 인스턴스 로컬에 `SseEmitter` 를 들고 있어 **스티키 세션** 또는 **Redis Pub/Sub 브로드캐스트** 같은 다음 단계가 필요합니다. 면접에서는 한계를 인정하고 개선안을 말하는 게 좋습니다.

---

### Q6. 지금 구조에서 아쉬운 점·개선하고 싶은 점은?

**A.** (1) **SSE 다중 인스턴스** (2) **직접 Kafka send 경로**(`HOLD_CREATED`, `PaymentComplete` 등)의 운영 재처리·모니터링을 outbox 수준으로 끌어올릴지 (3) 대기열 `removeExistingTokens` 의 ZSet 전체 스캔 (4) **홀드 TTL 연장**이 Lua 가 아닌 다중 명령인 점 — 를 코드 레벨에서 인지하고 있습니다.

> **Q6-1. 처음부터 다시 짠다면?**
> **A.** 트래픽 도메인(대기열·좌석 조회)을 **별도 서비스 또는 읽기 전용 API** 로 분리해 스케일 단위를 나누는 걸 검토합니다. 단일 모듈은 포트폴리오·온보딩에는 유리합니다.
