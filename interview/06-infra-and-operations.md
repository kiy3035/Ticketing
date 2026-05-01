# 인프라 / 운영

---

### 🟢 Q1. EC2 에 어떻게 올렸나요? 서버 구성을 설명해 주세요.

**A.** 4대 EC2 구성:
- **인프라 서버** (t3a.medium): Redis, Kafka, Prometheus, Grafana, nginx — Docker Compose
- **앱 서버 #1** (t3a.small): Spring Boot — Docker
- **앱 서버 #2** (t3a.small): Spring Boot — Docker (스케일아웃 후)
- **k6 서버** (t3a.small): 부하 테스트 전용

**현재 운영**: 앱 서버 1대로 운영 중. **ALB + 2대 스케일아웃** 예정. 앱은 환경변수(`.env` / spring-dotenv)로 DB/Redis/Kafka 접속 정보 주입.

> **🟢 Q1-1. nginx 는 왜 있나요?**
> **A.** ALB 도입 전 단계로 nginx 가 reverse proxy + 정적 자원/헬스체크 종단점 역할. ALB 도입 후에도 부하 테스트 시 단일 진입점으로 사용.

> **🟡 Q1-2. 스케줄러가 두 대에서 동시에 돌면?**
> **A.** 5종 스케줄러 모두 `lock:batch:*` Redis 분산 락으로 한 인스턴스만 실행. `RedisLockService.tryLock` (SETNX + TTL) → unlock Lua. TTL 은 가장 긴 배치(환불 5분)는 360초로 넉넉히.

---

### 🟡 Q2. 모니터링·메트릭은?

**A.** **Micrometer + Prometheus + Grafana** 스택.

**노출 엔드포인트**: `/actuator/prometheus` (Spring Actuator)

**커스텀 메트릭 예**:
- 비즈니스: `ticketing_hold_created_total`, `ticketing_reservation_confirmed_total{concert_id}`, `ticketing_payment_completed_total`
- 충돌/경합: `ticketing_lock_acquire_failures_total{operation}`, `ticketing_hold_conflict_total{reason}`
- Saga/Outbox: `ticketing_outbox_published_total`, `ticketing_outbox_publish_failures_total`
- 배치: `ticketing_batch_run_duration_seconds{batch}`, `ticketing_batch_run_total{batch,status}`
- 환불: `ticketing_refund_processed_total`
- HTTP histogram: `http_server_requests_seconds_bucket` (`management.metrics.distribution.percentiles-histogram.http.server.requests=true`)

**Grafana 대시보드**: 6패널 — 자세한 PromQL 은 `docs/load-test-portfolio.md`.

> **🟡 Q2-1. 알람은 어떤 지표부터 걸겠어요?**
> **A.** 1차 우선순위:
> - 에러율 (HTTP 5xx)
> - p99 레이턴시 급등
> - Kafka consumer lag (특히 `ticketing-payment-notification`)
> - Redis 연결 실패 / 서킷 OPEN 상태 지속
> - `kafka_outbox` FAILED 행 누적
> - `hikaricp_connections_pending` 지속

---

### 🟡 Q3. 헬스체크는 어떻게 구성했나요?

**A.** Spring Actuator `/actuator/health` + 자체 묶음 헬스 `TicketingDatastoresHealthIndicator`:
- **`ticketingDatastores`** 컴포넌트 = Redis PING + DB `Connection.isValid(2)` 둘 다 OK 면 UP, 하나라도 실패면 DOWN
- **Kafka 는 헬스에서 제외** (`management.health.kafka.enabled=false`) — 부하 시 60초 타임아웃 유발 위험. 별도 모니터링.
- 개별 indicator 도 있음: `DatabaseHealthIndicator`, `RedisHealthIndicator`, `KafkaHealthIndicator` (수동 호출용)

> **🟡 Q3-1. DB 는 살아 있는데 Redis 가 죽으면?**
> **A.** `ticketingDatastores` 가 DOWN → Kubernetes/ALB 가 트래픽 차단. 이 프로젝트는 Redis 없이 대기열·홀드가 불가능해서 **의도적으로 엄격** 하게 둔 선택입니다. 운영 정책에 따라 Redis 만 별도 컴포넌트로 빼서 부분 가용성 모드로 운영하는 것도 가능.

---

### 🟡 Q4. 배포 시 다운타임·롤링은?

**A.** **롤링 배포** 가정 (인스턴스 하나씩 새 버전으로 교체).
- **세션 없음** (JWT, stateless) → 재로그인 부담 없음, 스티키 불필요
- **공유 저장소**(Redis Access 블랙리스트 + DB `refresh_tokens`) 로 인스턴스 간 토큰 검증·폐기 일관성 유지
- **DB 마이그레이션**: Flyway (V1~V8), `baseline-on-migrate=true`, `IF NOT EXISTS` 패턴으로 호환

**한계 — SSE**: `SseNotificationService` 가 인스턴스 로컬에 `SseEmitter` 보유 → 배포 중 끊김. 다음 단계 개선안:
- 스티키 세션
- Redis Pub/Sub 으로 다른 인스턴스에 브로드캐스트
- 알림은 Redis List `notify:user:{userId}` 폴링으로도 받을 수 있어 SSE 끊김이 알림 누락은 아님

> **🔴 Q4-1. DB 마이그레이션 순서는?**
> **A.** Zero-downtime 배포라면 **호환 우선 패턴**:
> 1. 호환 마이그레이션 배포 (옛 컬럼 유지하면서 새 컬럼/인덱스 추가)
> 2. 새 코드 배포 (옛/새 컬럼 둘 다 읽고 새 컬럼에 쓰기)
> 3. 데이터 백필
> 4. 옛 컬럼 제거 마이그레이션 (V6 처럼)

---

### 🟢 Q5. 로그·트레이싱은?

**A.** Slf4j + Logback (`logback-spring.xml`). 운영 가시성이 필요한 지점은 명시적 로깅:
- 락 획득/해제, 대기열 진입, 홀드 생성/만료, 예약 확정, 결제 완료/실패/보상, 배치 시작/종료, outbox 재시도/FAILED 전환
- `userId`, `seatId`, `paymentId`, `concertId` 같은 식별자만 — 카드번호·전화번호 등 PII 는 마스킹/제외
- 분산 트레이싱(OpenTelemetry, Sleuth)은 다음 단계

> **🟡 Q5-1. PII 는 로그에 어떻게?**
> **A.** 카드 전체 번호·주민번호 등은 **남기지 않고**, `userId`/`reservationId` 같은 식별자만. 결제 로그도 `paymentKey` (UUID) 와 amount 만, 카드 raw 데이터는 토스 PG 가 보관.

---

### 🔴 Q6. Docker / Docker Compose 운영 포인트?

**A.** 인프라 서버 Docker Compose 에:
- **Redis**: `maxmemory 400mb`, `maxmemory-policy allkeys-lru`, `save ""` (RDB 비활성, 휘발성 운영)
- **Kafka**: 단일 브로커 (포트폴리오 규모) — 운영에서는 3브로커 + replication-factor 3
- **Prometheus**: 앱 서버 `/actuator/prometheus` scrape
- **Grafana**: Prometheus 데이터소스 + 대시보드 JSON 으로 부트스트랩

**앱 컨테이너**: JDK 21 base 이미지, Spring Boot fat jar. JVM 옵션은 컨테이너 메모리 인지 (`-XX:MaxRAMPercentage=75`).

> **🔴 Q6-1. 인프라가 단일 노드인데 SPOF 아닌가요?**
> **A.** 맞습니다. 포트폴리오 비용 제약상 단일 노드입니다. 운영 단계 개선안:
> - Redis: ElastiCache (Sentinel/Cluster) 매니지드
> - Kafka: MSK (관리형) + 3브로커 + replication
> - DB: RDS Multi-AZ
> - 헬스 + ALB 로 앱 서버 페일오버는 이미 가능

---

### 🔴 Q7. RDS 커넥션 한계는 어떻게 산정했나요?

**A.** HikariCP 설정 근거:
```properties
spring.datasource.hikari.maximum-pool-size=30
spring.datasource.hikari.minimum-idle=5
```
- **maximum-pool-size = 30**: 부하 테스트 (Phase 1) 에서 도출한 최적값
- **minimum-idle = 5**: 평시 서버당 5개만 유지 → 앱 2대 = 평시 10개 점유, 부하 시 max 60개까지 동적 확장
- RDS `max_connections` 한계의 70% 이내가 되도록 설계

VT (Tomcat 가상 스레드) 도입으로 동시 요청 수 한계가 풀렸지만, **DB 커넥션 풀이 새 병목** 으로 자연스럽게 이동 — VT 가 풀 대기 중에도 carrier thread 반납하므로 OS 스레드 점유 폭주는 없음.

---

### 🔴 Q8. 부하 테스트는 어떤 환경에서, 어떤 시나리오로?

**A.**
- **k6 서버**: 별도 EC2 t3a.small — 앱 서버와 분리해 부하 발생기 자체가 병목 안 되도록
- **타깃**: nginx (ALB 도입 전) → 앱 서버
- **시나리오** (`load-tests/`):
  - `queue-flow.js`: 대기열 진입→폴링 (백엔드 핵심 경로)
  - `knee-point.js`: VU 500→800→1000→1200→1500 계단식 (knee point 탐색)
  - `concurrent-hold.js`: 같은 좌석 100명 동시 홀드 → 1명만 성공 검증 (정합성)
  - `full-flow.js`: 로그인→대기열→홀드→결제 전체
  - `jwt-scenarios.js`: JWT 4가지 케이스
- Grafana 시간축을 부하 구간과 맞춰 메트릭 분석. 결과는 `portfolio/9. knee_point/`, `docs/load-test-portfolio.md` 에 정리.
