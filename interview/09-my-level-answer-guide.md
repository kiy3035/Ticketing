# 내 수준 맞춤 답변 가이드

면접에서 **내가 실제로 설명 가능한 수준**으로 답변하기 위한 개인 노트.
핵심은 어려운 용어보다 **문제-선택-결과-한계**를 명확히 말하는 것.

---

## 1) 기본 답변 구조 (30~60초)

모든 기술 질문에 4단으로:
1. **문제** — 어떤 장애·리스크를 줄이려 했는지
2. **선택** — 왜 그 방식을 택했는지 (대안 vs)
3. **결과** — 실제 코드/운영에서 얻은 효과
4. **한계** — 미완성·다음 개선 포인트

**템플릿**:
> "이 문제는 `<문제>` 가 핵심이라 `<선택>` 을 적용했습니다.
> 코드에서는 `<구현 위치>` 로 동작하고, 결과적으로 `<효과>` 를 얻었습니다.
> 다만 `<한계>` 가 있어서 다음엔 `<개선>` 을 하려 합니다."

---

## 2) Saga 보상 질문 답변

### 짧은 답변
> "완전한 분산 사가 오케스트레이션은 아니고, 결제 완료 단계에서 예약 확정 실패 시 보상을 분리 트랜잭션(REQUIRES_NEW)으로 처리한 실용형 사가입니다."

### 근거 (코드 기준)
- 메인 흐름: `PaymentService.completePayment()`
- 예약 확정 실패 시: `PaymentCompensationService.compensateAfterReservationFailure(paymentId)`
- 보상 트랜잭션: `@Transactional(propagation = REQUIRES_NEW)`
- 보상 내용:
  - POINT 결제: 포인트 환불 (Users PESSIMISTIC_WRITE)
  - 공통: 결제 상태 `CANCELED`, `canceledAt` 저장
- **멱등 가드**: `payment.status == CANCELED` 또는 `!= APPROVED` 면 즉시 return

### 면접 꼬리질문 대비
- **Q. 왜 REQUIRES_NEW?**
  → 메인 트랜잭션에서 예외를 다시 던지면 롤백되기 때문에, 보상 결과까지 같이 날아갈 수 있어서 분리.

- **Q. 이게 완전한 사가인가요?**
  → 아니요. 이벤트 기반 오케스트레이터/코레오그래피 전체를 구축한 건 아니고, 결제-예약 경계에 보상 로직을 명시적으로 넣은 형태. 보상 경계가 1곳뿐이라 단순 try-catch + REQUIRES_NEW 로 충분.

- **Q. CARD 결제 보상은?**
  → 현재 샌드박스 환경이라 DB CANCELED 만 처리. 실 운영에서는 토스 취소 API 호출이 추가되어야 함 — TODO.

---

## 3) 서킷브레이커 질문 답변

### 짧은 답변
> "Resilience4j 를 설정만 한 게 아니라, Queue/Hold 의 핵심 Redis 경로에 실제로 fast-fail + fallback 을 연결했습니다."

### 근거 (코드 기준)
- 공통 실행기: `RedisCircuitBreakerExecutor.execute(operation, action, fallback)`
- 적용 위치: `QueueService` (대기열 진입/조회/정리), `HoldStore` (홀드 생성/조회/해제/만료조회)
- 설정 (`application.properties`, `redisCircuitBreaker`):
  - sliding-window-size=10, failure-rate-threshold=50%
  - wait-duration-in-open-state=30s
  - permitted-number-of-calls-in-half-open-state=3
  - slow-call-duration-threshold=1s (Redis timeout 2s 보다 짧게 → 타임아웃 전 감지)
  - slow-call-rate-threshold=80%
- 동작:
  - CLOSED → action 실행, 통계 누적
  - OPEN → `CallNotPermittedException` 캐치 → 즉시 fallback
  - HALF_OPEN → 3회만 시험

### 면접 꼬리질문 대비
- **Q. 왜 전체 Redis 경로에 한 번에 안 붙였나요?**
  → 장애 영향이 큰 핵심 경로(대기열/홀드)부터 우선 적용. 일괄 적용보다 리스크가 낮고 검증이 쉽고, 점진적 확대 가능.

- **Q. fallback 이면 데이터 정합성 깨지지 않나요?**
  → 쓰기 경로(생성·진입)는 fail-closed 성향(false 반환)으로 사용자에게 "잠시 후 다시 시도" 응답. 조회성은 안전한 기본값(빈 결과, null) 으로 화면이 빈 상태로 그려지게.

- **Q. CircuitBreakerRegistry 빈을 직접 안 만든 이유?**
  → application.properties 의 `resilience4j.circuitbreaker.instances.redisCircuitBreaker.*` 설정이 자동 적용되도록. 직접 빈을 만들면 auto-config 가 건너뛰어 설정이 무시됨. 대신 `ResilienceConfig` 에서 Spring Boot 가 만든 Registry 를 주입받아 인스턴스만 가져옴.

---

## 4) Outbox 질문 답변

### 짧은 답변
> "예약 DB 커밋과 이벤트 발행 타이밍 불일치를 outbox + 스케줄러 재시도로 줄였습니다."

### 근거 (코드 기준)
- 적재: `KafkaOutboxService.enqueueSeatHoldEvent()` (`@Transactional`, 같은 TX 참여)
- `ReservationService.confirm()` 안에서 호출 → DB 커밋과 함께 outbox INSERT
- 발행: `KafkaOutboxPublishScheduler.publishPending()` (500ms 주기)
  - 분산 락 `lock:batch:kafka-outbox`
  - `TransactionTemplate.executeWithoutResult` (자기호출 함정 회피)
  - `kafkaTemplate.send().get(15s)` 로 동기 대기 → 성공 시 행 DELETE
  - 실패 시 publishAttempts++, 25회 초과 시 FAILED

### 면접 꼬리질문 대비
- **Q. 왜 모든 이벤트에 outbox 를?**
  → "DB 커밋과 반드시 묶여야 하는 발행" 만 outbox. `RESERVATION_CONFIRMED` 만 해당. `HOLD_*`, `PaymentComplete` 는 직접 send (간단성 우선). 트레이드오프를 코드 구조로 설명.

- **Q. 발행 성공 시 SENT 상태로 두지 않고 DELETE 하나요?**
  → 운영 메모리·인덱스 효율. SENT 상태로 쌓으면 별도 archival 정책 필요. 실패 추적은 FAILED 행만 남으므로 충분.

- **Q. 같은 outbox 행이 두 번 발행될 가능성은?**
  → at-least-once. 컨슈머는 비즈니스 상태 가드로 멱등 처리. 분산 락으로 두 인스턴스 동시 실행은 차단.

---

## 5) Virtual Thread 질문 답변

### 짧은 답변
> "I/O 대기가 99% 인 워크로드라 Tomcat 요청 + Kafka 리스너 + 만료 정리 배치에 적용했습니다. CPU 위주이거나 라이브러리 자체 스레드 영역에는 적용하지 않았습니다."

### 근거 (코드 기준)
- Tomcat: `spring.threads.virtual.enabled=true` 한 줄
- 스케줄러 병렬: `HoldCleanupScheduler`, `RefundForCancelledConcertScheduler` 가 `Executors.newVirtualThreadPerTaskExecutor()` 로 청크 병렬
- Kafka 리스너: `KafkaConfig.virtualThreadExecutor()` 로 두 ListenerContainerFactory 모두 적용
- **적용 안 한 곳**: 스케줄러 트리거 스레드(가벼움), `QueueProcessingScheduler` 내부(0.5ms 단위 호출), Lettuce/Kafka Producer 내부(Netty/sender 스레드는 별도 관리)

### 면접 꼬리질문 대비
- **Q. WebFlux 와 비교하면?**
  → WebFlux 는 전체 코드를 Mono/Flux 로 재작성, JPA → R2DBC 필요. VT 는 기존 동기 코드 그대로 두면서 I/O 효율만 개선. 코드 복잡도 vs 성능 이득에서 VT 가 적합.

- **Q. Pinning 문제는?**
  → 직접 `synchronized` 사용 안 함. MySQL Connector/J 9.x, Lettuce, Spring 6.1 모두 내부 락이 ReentrantLock 으로 교체. `-Djdk.tracePinnedThreads=short` 로 검증 가능.

---

## 6) "너무 과한 설계 아니냐" 질문 답변

### 짧은 답변
> "처음부터 거대한 구조를 한 번에 넣은 게 아니라, 실제로 문제를 겪은 구간(좌석 경합, 결제-예약 경계, Redis 장애 전파)에만 단계적으로 적용했습니다."

### 말하면 좋은 포인트
- 무조건 최신/복잡한 기술이 아니라 **문제 중심으로 국소 적용**
- "적용한 것"과 "아직 안 한 것"을 구분 (예: outbox 는 RESERVATION_CONFIRMED 만)
- 개선 계획을 과장하지 않고 현실적으로 제시 (예: 단일 Redis SPOF — Sentinel/Cluster는 다음 단계로 인지)

---

## 7) 금지 문장 (면접에서 피하기)

- "그냥 GPT가 추천해서 넣었습니다."
- "정확히는 모르는데 돌아가긴 합니다."
- "완벽하게 무결합니다."

대신:
- "현재 범위에서는 이 리스크를 줄였습니다."
- "여기까지는 구현했고, 여기부터는 다음 단계입니다."
- "트레이드오프를 인지하고 X 를 선택했습니다."

---

## 8) 내 프로젝트 기준 한 줄 정리

| 주제 | 한 줄 |
|------|------|
| **사가** | 결제-예약 경계의 보상 트랜잭션을 REQUIRES_NEW 로 분리해 outer 롤백과 무관하게 보상 커밋이 살아남게 했다. |
| **서킷브레이커** | Redis 장애가 전체로 전파되지 않도록 Queue/Hold 핵심 경로에 fast-fail + fallback 을 실제 연결했다. |
| **아웃박스** | 예약 DB 커밋과 이벤트 발행 타이밍 불일치를 outbox + 스케줄러 재시도(최대 25회→FAILED) 로 줄였다. |
| **Virtual Thread** | Tomcat + 만료/환불 배치 + Kafka 리스너에 적용해 I/O 대기 중 carrier thread 점유를 없앴다. |
| **JWT** | Refresh jti DB 저장·폐기 + Redis Access 블랙리스트로 stateless 의 한계(즉시 무효화)를 보완. 회전·탈취 탐지는 트레이드오프(정상 사용자 강제 로그아웃)를 고려해 의도적으로 단순 구조 채택. |
| **분산 락** | 좌석 단위(`lock:seat:*`) + 배치 단위(`lock:batch:*`) 로 동시 선점·중복 실행을 모두 막고, unlock 은 Lua 토큰 검증. |
| **이중 방어** | Redis 락 → Lua EXISTS → DB seat.status → DB 트랜잭션 4단으로 좌석 경합을 막고 어느 한 단계가 실패해도 정합성 유지. |

---

## 9) 면접 시간 분배 팁

- **개요(자기소개·프로젝트 소개)**: 1~2분 — "콘서트 예매 백엔드, 동시 선점·대기열·결제 정합성이 핵심"
- **각 기술 질문**: 30~60초 (4단 구조)
- **꼬리 질문**: 답변에 한계·개선 포인트를 살짝 흘려 면접관이 그 방향으로 깊이 들어오게 유도
- **모르는 질문**: "지금은 인지하지 못하고 있습니다. X 를 더 학습한 뒤 답변드리겠습니다" — 모름을 인정하는 게 추측보다 안전
