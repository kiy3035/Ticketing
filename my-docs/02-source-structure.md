# 02. 소스 구조 (패키지·클래스 역할)

`src/main/java/com/inyoung/ticketing/` 기준으로, **어떤 패키지가 무슨 일을 하는지**, **핵심 클래스는 어디서 호출되는지** 정리했다.

---

## 1. 패키지별 요약

| 패키지 | 역할 | 핵심 클래스 |
|--------|------|-------------|
| **auth** | 회원가입, 로그인 사용자 조회, 마이페이지 | AuthApiController, UsersService, Users, UsersRepository |
| **concert** | 공연 목록/상세, 카테고리·과거 공연 필터 | ConcertController, ConcertService, Concert, ConcertRepository |
| **seat** | 좌석 목록(DB + Redis 홀드 반영) | SeatController, SeatService, Seat, SeatRepository |
| **queue** | 대기열 진입/순번/입장 허용/퇴장 | QueueController, QueueService |
| **hold** | 좌석 홀드 생성·취소·내 홀드 목록 | HoldController, HoldService, HoldStore, HoldInfo |
| **lock** | Redis 분산 락 (좌석 단위) | LockService(인터페이스), RedisLockService |
| **reservation** | 예약 확정(내부 호출 전용), 예매 내역 조회, 환불 시 취소 | ReservationController(GET /me만), ReservationService, ReservationConfirmedEvent/Listener |
| **payment** | 결제 요청/승인/완료/취소, 토스 연동, 환불 배치 호출 | PaymentController, PaymentService, TossPaymentsClient, RefundForCancelledConcertScheduler에서 paymentService.refund... 호출 |
| **notification** | 알림 저장, SSE 스트림, 결제 완료 시 이메일/SMS | NotificationController, NotificationSseController, NotificationService, SseNotificationService, PaymentNotificationService |
| **admin** | 통계, 유저/결제 목록, 미판매 좌석 | AdminController, AdminService |
| **seller** | 판매자용 공연·좌석·예약·매출·공연 취소 | SellerController, SellerService |
| **config** | Security, Redis, Kafka, Session, TicketingProperties 등 | SecurityConfig, RedisConfig, KafkaConfig, TicketingProperties |
| **common** | 공통 응답, 예외 처리, 캐시, **멱등·레이트리밋** | ApiResponse, GlobalExceptionHandler, `common.cache`, Idempotency*, RateLimit* |
| **health** | DB+Redis 묶음(`ticketingDatastores`), 개별 지표 등 | TicketingDatastoresHealthIndicator, DatabaseHealthIndicator, RedisHealthIndicator, KafkaHealthIndicator |
| **metrics** | 접속자 수, 대기열 메트릭 API | MetricsController, ActiveUserTracker, QueueMetrics |
| **outbox** | 예약 확정 시 Kafka 발행 의무를 DB 에 적재 | KafkaOutboxService, KafkaOutboxRepository, `KafkaOutbox` 엔티티 |
| **scheduler** | 대기열·홀드·환불·**Outbox 발행** 배치 | QueueProcessingScheduler, QueueCleanupScheduler, HoldCleanupScheduler, RefundForCancelledConcertScheduler, **KafkaOutboxPublishScheduler** |

---

## 2. 호출 관계 (흐름 위주)

- **예매 한 번에 타는 경로**  
  ConcertController → SeatController → HoldController → PaymentController  
  → PaymentService.completePayment() → **ReservationService.confirm()**  
  → 같은 트랜잭션에서 예약·좌석 저장 + **`kafkaOutboxService.enqueueSeatHoldEvent(RESERVATION_CONFIRMED)`**  
  → 커밋 후 **ReservationConfirmedEventListener** (AFTER_COMMIT) → **`HoldStore.releaseHold()` 만**  
  → **`KafkaOutboxPublishScheduler`** 가 outbox 행을 Kafka 로 전송 후 행 삭제

- **홀드 생성**  
  HoldController → HoldService.createHold() → LockService.tryLock() → SeatRepository, HoldStore.createHold(), SeatHoldEventPublisher

- **대기열**  
  QueueController → QueueService (enterQueue, getRank, isAllowed 등), 입장 허용은 QueueProcessingScheduler가 QueueService.allowEntry() 호출

- **환불**  
  RefundForCancelledConcertScheduler → PaymentService.refundCompletedPaymentForCancelledConcert() → ReservationService.cancelReservationForRefund(), refundPoints(), Payment CANCELED 저장

---

## 3. 중요한 인터페이스·구현

- **LockService** → 구현체 **RedisLockService** (좌석 락)
- **HoldStore** — Redis 전용, 인터페이스 없음. HoldService, ReservationService, PaymentService, SeatService(좌석 목록 시 홀드 여부), QueueController(가용 좌석 수)에서 사용
- **ReservationService.confirm()** — PaymentService.completePayment()에서만 호출. 컨트롤러에는 예약 확정용 POST 없음 (결제 완료 시에만 예약 확정)

---

## 4. 설정·프로퍼티

- **TicketingProperties**: 락 TTL/재시도, 홀드 TTL, 대기열 주기/임계치, 결제 홀드 연장, 환불 배치 주기/크기, Kafka 토픽 등
- **application.properties**: DB, Redis, Kafka, `ticketing.*` 키로 위 설정 값

자세한 키 이름·기본값은 `docs/infra.md`, `docs/data.md` 또는 `my-docs/06-redis-kafka-reference.md` 참고.
