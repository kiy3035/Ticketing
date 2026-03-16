# 아키텍처 / 설계

---

### Q1. 이 프로젝트의 전체 아키텍처를 간단히 설명해 주세요.

**A.** 클라이언트 → Spring Boot API 서버 → MySQL/Redis/Kafka로 구성된 레이어드 아키텍처입니다. API 서버 내부는 `Controller → Service → Store/Repository`로 나뉘고, 그 위에 Spring Security + Redis Session 기반 인증 레이어, 옆으로 Kafka 기반 Event Layer(`SeatHoldEventPublisher/Consumer`, `PaymentCompleteEventPublisher/Consumer`)와 Scheduler Layer(`QueueProcessingScheduler`, `HoldCleanupScheduler`, `RefundForCancelledConcertScheduler`)를 두어 책임을 분리했습니다. MySQL은 결제·예약·좌석 같은 영구 데이터를 담당하고, Redis는 대기열·홀드·락·세션 등 실시간성이 필요한 휘발성 데이터를 전담합니다.

> **Q1-1. Controller에서 Service까지는 일반적인데, Store라는 레이어를 별도로 둔 이유가 있나요?**
> **A.** `HoldStore`나 `RedisLockService`처럼 Redis에 직접 접근하는 코드를 Service 안에 넣으면, `ZADD`·`SET`·`EXPIRE` 같은 Redis 명령이 비즈니스 로직과 섞여 가독성이 떨어집니다. 그래서 "도메인 서비스는 비즈니스 용어만 사용하고, Redis 자료구조/명령어는 Store 안에 캡슐화한다"는 기준을 세웠습니다. 예를 들어 `HoldService`는 `holdStore.createHold(info, ttl)`만 호출하고, 실제 Lua 스크립트 실행과 `hold:seat:{seatId}`·`hold:token:{token}`·`hold:expires` ZSet 갱신은 `HoldStore` 내부에서 처리합니다.

---

### Q2. Kafka를 왜 도입하셨나요? 동기 호출로도 가능할 것 같은데요.

**A.** 결제 완료 후 이메일·SMS 알림은 사용자 응답 시간과 직접적 상관이 없어서 비동기로 분리했습니다. `PaymentService.completePayment()`는 예약 확정과 결제 상태 변경만 동기로 처리하고, 이후 `PaymentCompleteEventPublisher.publishPaymentComplete()`로 Kafka에 이벤트를 발행합니다. Consumer 쪽(`PaymentCompleteEventConsumer → NotificationService → EmailService`)은 별도로 동작하기 때문에, 알림 채널을 추가하거나 재시도/DLT를 붙일 때 결제 로직을 건드리지 않아도 됩니다. 홀드 이벤트도 마찬가지로 `SeatHoldEventPublisher`가 `ticketing.seat-hold-events` 토픽에 `HOLD_CREATED`·`HOLD_EXPIRED`·`RESERVATION_CONFIRMED` 등을 발행하고, `SeatHoldEventConsumer`가 알림을 처리합니다.

> **Q2-1. Kafka가 장애 나면 결제가 실패하나요?**
> **A.** 아닙니다. 설계 원칙이 "결제가 알림에 의존하면 안 된다"이기 때문에, `completePayment()` 안에서 DB 트랜잭션(좌석 RESERVED + Reservation 생성 + Payment COMPLETED)이 먼저 커밋됩니다. Kafka 발행이 실패하더라도 결제/예약 자체는 정상 커밋되고, 알림만 누락됩니다. 운영 환경에서는 Dead Letter Topic + 재처리 배치로 보완하는 것을 전제로 설계했습니다.

---

### Q3. `ReservationConfirmedEventListener`의 `AFTER_COMMIT` 패턴은 왜 사용하셨나요?

**A.** `ReservationService.confirm()`에서 좌석을 RESERVED로 바꾸고 Reservation을 생성한 뒤, DB 커밋이 **성공한 후에만** Redis 홀드를 해제하고 Kafka 이벤트를 발행해야 합니다. 만약 트랜잭션 안에서 바로 `holdStore.releaseHold()`를 호출하면, 이후 예외로 롤백될 경우 "DB에는 예약이 없는데 Redis 홀드는 이미 해제된" 불일치가 생깁니다. 그래서 `applicationEventPublisher.publishEvent(new ReservationConfirmedEvent(...))`로 Spring 이벤트를 발행하고, `ReservationConfirmedEventListener`에서 `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`으로 받아 `holdStore.releaseHold()`와 `eventPublisher.publish(RESERVATION_CONFIRMED, ...)`를 수행합니다.

> **Q3-1. AFTER_COMMIT에서 실패하면 홀드가 영구히 남는 거 아닌가요?**
> **A.** 맞습니다. 하지만 `HoldCleanupScheduler`가 1분마다 `hold:expires` ZSet을 스캔해서 만료된 홀드를 `releaseByPayload()`로 정리하기 때문에, 최악의 경우에도 홀드 TTL(기본 10분, 결제 중 20분 연장) 이내에 정리됩니다. DB가 source of truth이므로, 좌석이 이미 RESERVED로 확정된 상태에서 홀드가 잠시 남아있어도 기능에 영향은 없습니다.

---

### Q4. Repository(JPA)와 Store(Redis)를 함께 사용할 때 기준을 어떻게 나누셨나요?

**A.** 1차 기준은 "영속성이 필요한가"입니다. 예매 내역·결제 내역·좌석 상태처럼 감사·정산·추적이 필요한 데이터는 MySQL + JPA Repository에 저장했습니다. 반대로 대기열 토큰(`queue:token:{token}`), 홀드(`hold:seat:{seatId}`), 좌석 락(`lock:seat:{seatId}`), 세션(`ticketing:sessions:*`)처럼 시간에 민감하고 사라져도 복구 가능한 데이터는 Redis Store에 두었습니다. 두 저장소가 동시에 관여하는 플로우(예: 예약 확정 시 DB 업데이트 + Redis 홀드 해제)는 앞서 설명한 `AFTER_COMMIT` 패턴으로 트랜잭션 경계를 맞췄습니다.

> **Q4-1. 그럼 Redis가 죽으면 전체 서비스가 중단되나요?**
> **A.** 현재 구조에서는 Redis가 죽으면 대기열 진입·좌석 홀드·락 획득이 불가능해지므로 예매 기능은 사실상 중단됩니다. 다만 이미 DB에 저장된 예약/결제 데이터는 영향을 받지 않아 조회나 관리자 기능은 정상 동작합니다. 이 리스크를 줄이려면 Redis Sentinel이나 Cluster 구성이 필요하고, 현재 포트폴리오 스펙(t3a.medium 1대)에서는 단일 Redis를 사용하되, 키 네이밍을 `prefix:domain:sub` 형태로 일관되게 맞춰 샤딩 전환이 용이하도록 준비했습니다.

---

### Q5. 스케일아웃을 어떻게 고려하셨나요?

**A.** 첫째, 애플리케이션 서버를 Stateless에 가깝게 설계했습니다. 세션·홀드·대기열·락 등 모든 상태를 Redis에 두었기 때문에 인스턴스를 늘려도 세션 공유 문제가 없습니다. 둘째, Kafka Consumer는 Consumer Group을 활용해 파티션 기준으로 자동 분산 소비됩니다. 셋째, 스케줄러(`QueueProcessingScheduler`, `HoldCleanupScheduler`, `RefundForCancelledConcertScheduler`)는 모두 `lock:batch:*` 키로 분산 락을 잡아 다중 인스턴스에서도 한 노드만 실행되도록 했습니다.

> **Q5-1. SSE 연결은 스케일아웃 시 문제가 되지 않나요?**
> **A.** 맞습니다. `SseNotificationService`는 `ConcurrentHashMap<String, SseEmitter>`로 인스턴스 로컬 메모리에 연결을 관리하기 때문에, 같은 사용자가 다른 인스턴스로 요청이 가면 알림을 받지 못합니다. 그래서 로드밸런서 레벨에서 Sticky Session을 전제로 했고, 완전한 해결을 위해서는 Redis Pub/Sub이나 별도 메시지 브로커를 통해 인스턴스 간 SSE 이벤트를 브로드캐스트하는 구조로 개선해야 합니다.

---

### Q6. 현재 아키텍처에서 가장 아쉬운 점이나 개선하고 싶은 부분은요?

**A.** 첫째, SSE를 인스턴스 로컬에서 관리하는 부분이 가장 아쉽습니다. Redis Pub/Sub 기반으로 전환하면 Sticky Session 제약을 없앨 수 있습니다. 둘째, `QueueService.removeExistingTokens()`에서 ZSet 전체를 `range(0, -1)`로 조회하는 부분은 대기열이 커지면 성능 이슈가 될 수 있어, userId 기반 역인덱스 키를 추가하는 것을 검토하고 있습니다. 셋째, Kafka 이벤트 발행 실패 시 재처리 메커니즘(DLT + 재시도 배치)이 아직 구현되어 있지 않아, 운영 환경에서는 이 부분을 추가해야 합니다.

> **Q6-1. 만약 처음부터 다시 설계한다면 무엇을 바꾸실 건가요?**
> **A.** 대기열 서비스를 별도 모듈이나 마이크로서비스로 분리하는 것을 고려할 것 같습니다. 현재는 단일 Spring Boot에 모든 기능이 들어 있어 배포와 스케일링이 한 덩어리로 묶여 있습니다. 대기열은 트래픽 특성이 다른 도메인(읽기 위주, 짧은 응답 시간 요구)이라 별도로 스케일링할 수 있으면 효율적입니다.
