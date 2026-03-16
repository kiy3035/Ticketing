# 인프라 / 운영

---

### Q1. 인프라 구성과 설계 목표를 설명해 주세요.

**A.** t3a.medium 1대(인프라: Redis, Kafka, Zookeeper, Kafka UI, Redis Insight, Prometheus, Grafana)와 t3.small 2대(애플리케이션 서버)로 구성했습니다. 앱 서버는 상태를 Redis/MySQL에 위임해 Stateless에 가깝고, ALB로 트래픽을 분산합니다. 스케줄러 4종(`QueueProcessingScheduler`, `QueueCleanupScheduler`, `HoldCleanupScheduler`, `RefundForCancelledConcertScheduler`)은 모두 `lock:batch:*` 분산 락으로 한 노드만 실행되므로, 2대에서 중복 실행되지 않습니다.

> **Q1-1. SSE 연결은 Stateless가 아닌데, 2대에서 어떻게 처리하나요?**
> **A.** `SseNotificationService`는 `ConcurrentHashMap<String, SseEmitter>`로 인스턴스 로컬에 연결을 관리하기 때문에, ALB에서 Sticky Session을 설정해 같은 사용자가 같은 인스턴스로 라우팅되도록 해야 합니다. 완전한 해결을 위해서는 Redis Pub/Sub로 인스턴스 간 이벤트를 브로드캐스트하는 구조가 필요하고, 이 부분은 현재 아키텍처의 제약으로 인지하고 있습니다.

---

### Q2. Docker Compose에서 리소스 제한을 어떻게 설정하셨나요?

**A.** Redis는 컨테이너 메모리 512MB + 내부 `--maxmemory 400mb --maxmemory-policy allkeys-lru --save ""`로, Kafka는 512MB, Zookeeper/Kafka UI/Redis Insight는 각 256MB로 제한했습니다. t3a.medium(4GB RAM) 안에서 각 서비스가 메모리를 독식하지 않도록 하기 위해서입니다. Redis `save ""`는 RDB 스냅샷을 비활성화해 디스크 I/O를 없앤 것이고, 이 프로젝트에서 Redis 데이터는 모두 휘발성(DB가 source of truth)이므로 persistence가 불필요합니다.

> **Q2-1. Redis 400mb가 부족해지면 어떻게 되나요?**
> **A.** `allkeys-lru` 정책이 적용되어 가장 오래 접근하지 않은 키부터 eviction됩니다. 먼저 TTL을 줄이는 방향(대기열 토큰 30분→10분, 홀드 10분→5분)으로 대응하고, 그래도 부족하면 maxmemory를 늘리거나 Redis Cluster로 샤딩합니다. 키 네이밍이 `prefix:domain:sub` 패턴이라 해시 슬롯 기반 분리가 용이합니다.

---

### Q3. 모니터링은 어떤 지표를 보나요?

**A.** 세 축으로 나눕니다. 인프라 레벨은 `docker stats`로 컨테이너 CPU/메모리, 애플리케이션 레벨은 Actuator + Prometheus로 HTTP 레이턴시/에러율과 커스텀 메트릭, 스토리지 레벨은 Redis Insight(키 수, 메모리)와 Kafka UI(토픽/컨슈머 상태)입니다. 커스텀 메트릭으로 `ticketing_queue_waiting_count`(QueueMetrics Gauge), `ticketing_holds_active_count`(HoldMetrics Gauge), `ticketing_hold_created_total`, `ticketing_lock_acquire_failures_total`, `ticketing_payment_completed_total`, `ticketing_batch_run_duration_seconds` 등을 Prometheus에 노출하고 Grafana 대시보드로 확인합니다.

> **Q3-1. 부하 테스트 중 knee point는 어떤 메트릭으로 판단하나요?**
> **A.** `http_server_requests` p95 레이턴시가 급격히 올라가는 시점이 1차 지표이고, 그 시점에서 `ticketing_lock_acquire_failures_total`이 급증하면 Redis 락 경합 병목, DB 커넥션 풀 대기 시간이 늘면 MySQL 병목으로 판단합니다. `ticketing_batch_run_duration_seconds`로 스케줄러 실행 시간이 주기보다 길어지는지도 확인합니다.

---

### Q4. 환경변수/설정 관리는 어떻게 하셨나요?

**A.** 민감 정보(DB 비밀번호, Redis 호스트, Kafka 주소, Toss 키, 메일 비밀번호, Solapi 키)는 `.env` 파일로 관리하고 `.gitignore`에 포함시켜 깃에 커밋하지 않습니다. `application.properties`에서 `${DB_URL:기본값}` 형태로 주입받아, 환경변수가 없으면 로컬 개발용 기본값이 적용됩니다. 튜닝에 중요한 값(`ticketing.queue.batch-size`, `ticketing.hold.ttl-seconds`, `ticketing.lock.ttl-seconds` 등)은 모두 `TicketingProperties`에 바인딩해서 코드 변경 없이 properties/환경변수로 조정 가능합니다.

> **Q4-1. 운영 환경에서는 `.env` 대신 어떤 방식을 쓰실 건가요?**
> **A.** EC2에서는 시스템 환경변수나 AWS Parameter Store/Secrets Manager를 사용할 계획입니다. 현재 `application.properties`가 이미 `${ENV_KEY:default}` 패턴이므로, `.env` 파일을 제거하고 환경변수만 세팅하면 바로 동작합니다.

---

### Q5. CI/CD 파이프라인은 어떻게 구성하셨나요?

**A.** `.github/workflows/deploy-prod.yml`에 GitHub Actions 워크플로우를 구성했습니다. main 브랜치 push 시 Gradle 빌드 → JAR 생성 → EC2에 SCP로 전송 → SSH로 기존 프로세스 종료 후 `nohup java -jar`로 재시작하는 구조입니다. 현재는 롤링 배포 수준이지만, 2대의 앱 서버에 순차 배포하면 무중단에 가까운 배포가 가능합니다.

> **Q5-1. 배포 중 요청 유실은 어떻게 방지하나요?**
> **A.** ALB 헬스체크(`/actuator/health`)가 실패하면 해당 인스턴스로 트래픽을 보내지 않으므로, 한 대씩 순차 배포하면 나머지 한 대가 트래픽을 받습니다. 세션은 Redis에 있고, 대기열/홀드/락도 Redis 기반이라 인스턴스가 바뀌어도 상태가 유지됩니다.
