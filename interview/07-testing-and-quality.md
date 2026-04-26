# 테스트 / 품질

---

### 🟢 Q1. 어떤 테스트들이 있나요?

**A.** 단위/통합 모두 있습니다 (`src/test/java`):
- **단위**: `RedisLockServiceTest`, `QueueServiceTest`, `HoldServiceTest`, `IdempotencyServiceTest`, `RateLimitServiceTest`
- **통합 (Testcontainers)**: `HoldControllerIntegrationTest`, `QueueControllerIntegrationTest`, `IntegrationTestBase`
- **동시성**: `RedisLockConcurrencyTest`, `SeatHoldConcurrencyTest`
- **아키텍처**: `ArchitectureTest` (ArchUnit)
- **부하**: k6 (`load-tests/`)

---

### 🟡 Q2. 통합 테스트 인프라를 어떻게 구성했나요?

**A.** `IntegrationTestBase` 가 **Testcontainers** 로 컨테이너 띄우고 `@DynamicPropertySource` 로 Spring 프로퍼티 바인딩:
- MySQL 8
- Redis 7
- Kafka (Confluent 7.5)

로컬·CI 모두 **Docker 만 있으면** 동일 동작. 테스트 프로파일에서는 Flyway 끄고 JPA `create-drop` 으로 스키마 생성. 외부 메일·SMS 는 더미 호스트/키로 막아 네트워크 의존 없이 API·서비스 통합 검증.

> **🟡 Q2-1. E2E 전부 Testcontainers 로만?**
> **A.** 핵심 플로우는 컨테이너 기반 통합 테스트, 단위는 Mockito. 부하는 별도 k6. 컨테이너 띄우는 시간 비용이 있어서 매번 풀 부트 통합은 비효율.

---

### 🟡 Q3. ArchUnit 을 왜 넣었고 어떤 규칙인가요?

**A.** 레이어 위반은 리뷰 누락 시 되돌리기 비싸서 CI 에서 깨지게 고정. `ArchitectureTest` 규칙:
1. `..controller..` 패키지가 `..repository..` 에 **직접 의존하지 않음** (반드시 Service 경유)
2. `..domain..` 이 `org.springframework..` 에 **의존하지 않음** (도메인 순수성)
3. `..service..` 가 `..controller..` 를 **참조하지 않음**

→ "왜 컨트롤러에서 Repository 를 안 쓰나?" 질문에 **테스트가 근거**가 됩니다.

> **🟡 Q3-1. ArchUnit 이 너무 빡빡하면?**
> **A.** 팀이 동의한 최소 세트만. 포트폴리오 규모에 맞춰 3가지만. 필요해지면 `..config..` 예외나 store/event 패키지 추가 규칙.

---

### 🔴 Q4. 동시성·락은 테스트로 어떻게 검증하나요?

**A.** 단일 JVM 통합 테스트만으로 레이스를 재현하기 어렵기 때문에 3가지 조합:
1. **단위 테스트**: `RedisLockServiceTest` 가 락 획득/해제, TTL 만료, 토큰 불일치 unlock 거부 검증
2. **멀티스레드 통합 테스트**: `SeatHoldConcurrencyTest`, `RedisLockConcurrencyTest`
   ```java
   ExecutorService executor = Executors.newFixedThreadPool(100);
   CountDownLatch readyLatch = new CountDownLatch(100);
   CountDownLatch startLatch = new CountDownLatch(1);
   // 100개 스레드가 readyLatch 카운트다운 후 startLatch 대기
   // → 일제히 출발해 같은 좌석 홀드 시도
   // → assertThat(successCount.get()).isEqualTo(1);
   ```
3. **k6 부하 스크립트** (`concurrent-hold.js`): 실제 분산 환경에서 경합·knee point 확인

면접에서는 "동시성 버그는 테스트 한 방으로 끝나지 않는다" — 설계(원자성·이중 방어) + 관측(메트릭) 도 함께 말하기.

> **🔴 Q4-1. 멀티스레드 테스트는 flaky 하지 않나요?**
> **A.** 가능합니다. `CountDownLatch` 로 동시 출발 보장, `assertThat` 으로 명확한 후처리 조건, Testcontainers Redis 격리로 노이즈 제거. 그래도 flaky 하면 부하 스크립트 결과를 보조 근거로.

---

### 🟡 Q5. 결제·Outbox 멱등은 어떻게 테스트하나요?

**A.**
- **HTTP `Idempotency-Key`**: 동일 키·동일 바디 재요청 → 동일 응답 검증 (통합 테스트). `IdempotencyServiceTest` 가 PROCESSING 마커, 결과 캐시, 재시도 허용 검증.
- **Outbox**: `KafkaOutboxPublishScheduler` 동작은 통합 테스트로 직접 검증 가능 (Testcontainers Kafka). 행 INSERT 후 스케줄러 호출 → Kafka consumer 가 받았는지 + outbox 행이 DELETE 됐는지.
- **Kafka 컨슈머**: 동일 메시지 두 번 줘도 비즈니스 상태 가드(예: `payment.status == COMPLETED`)로 멱등.

> **🟡 Q5-1. 테스트 데이터는 어떻게 정리하나요?**
> **A.** Testcontainers 가 컨테이너 단위 격리. `@Transactional` 롤백, 테스트마다 `create-drop`. Kafka 토픽은 테스트별로 prefix 다르게 두거나 컨테이너 재기동.

---

### 🔴 Q6. CI 에서 무엇을 게이트로 두나요?

**A.**
- **단위 + 통합 테스트 통과**
- **ArchUnit 통과** (레이어 규칙)
- (선택) 정적 분석/포맷 (Checkstyle, SpotBugs)
- Docker 가 없는 CI 면 Testcontainers 가 실패 → CI 에 Docker-in-Docker 또는 원격 Docker 필요

> **🔴 Q6-1. 테스트가 부하 테스트까지 커버하나요?**
> **A.** k6 부하 테스트는 별도 EC2 (`k6 서버`) 에서 수동 실행. CI 마다 돌리기에는 비용·시간 부담. 대신 동시성 단위 테스트(`SeatHoldConcurrencyTest`) 가 정합성 회귀를 매 PR 마다 잡아냅니다.

---

### 🔴 Q7. 코드 품질 / 일관성 유지 전략은?

**A.**
- **CLAUDE.md** 에 코드 규칙 명시:
  - 주석 한국어
  - Slf4j 로깅 (락 획득/해제, 대기열 진입, 예매 완료 같은 운영 가시성 지점은 필수)
  - 매직넘버·임계치 하드코딩 금지 → `application.properties` 또는 환경변수 외부화
  - 새 기능 구현 전 설계 방향 논의
  - 스케일아웃 환경 항상 염두 (세션 공유, 락 분산)
- **`TicketingProperties`** 로 모든 임계치를 `@ConfigurationProperties` 로 묶어 환경별 오버라이드 가능
- **Flyway 버전 관리** 로 DB 변경 추적
- **메트릭 + 로그 + 트레이스(차후)** 로 운영 회귀 빠르게 감지
