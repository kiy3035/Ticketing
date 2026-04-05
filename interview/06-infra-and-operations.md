# 인프라 / 운영

---

### Q1. EC2 에 어떻게 올렸나요?

**A.** [docs/deployment-ec2.md](../docs/deployment-ec2.md) 기준으로 **t3a.medium 1대**(Redis·Kafka·Prometheus·Grafana 등) + **t3.small 2대**(Spring Boot) 구성을 가정합니다. 앱은 **Docker** 이미지로 빌드해 EC2 에 배포하고, ALB 로 **HTTP 헬스**를 검사합니다. Kafka·Redis 는 인프라 호스트 또는 컨테이너로 두고, 앱은 **환경 변수**로 접속 정보를 받습니다.

> **Q1-1. 스케줄러가 두 대에서 동시에 돌면?**
> **A.** `QueueProcessingScheduler`, `QueueCleanupScheduler`, `HoldCleanupScheduler`, `RefundForCancelledConcertScheduler`, **`KafkaOutboxPublishScheduler`** 다섯 스케줄러가 각각 **`lock:batch:*` Redis 락**으로 **한 인스턴스만** 배치를 실행합니다.

---

### Q2. 모니터링·메트릭은?

**A.** **Micrometer + Prometheus** 로 JVM·HTTP·커스텀 메트릭을 노출하고 **Grafana** 로 시각화합니다. Kafka lag, Redis 메모리, 앱 인스턴스 수, 스케줄 락 경합을 같이 보면 **knee point** 분석에 도움이 됩니다. 부하 스크립트는 `load-tests/` 를 사용합니다.

> **Q2-1. 알람은 어떤 지표부터 걸겠어요?**
> **A.** **에러율·p99 지연·Kafka consumer lag·Redis 연결 실패·outbox FAILED 누적** 정도를 1차로 둡니다.

---

### Q3. 헬스 체크는 어떻게 구성했나요?

**A.** Spring Boot Actuator **`/actuator/health`** 를 쓰고, **`ticketingDatastores`** 컴포넌트로 **MySQL + Redis** 연결을 묶어 **liveness/readiness** 판단에 쓸 수 있게 했습니다. **Kafka 는 기본 헬스에서 제외**해 브로커 일시 장애가 앱 전체를 Unhealthy 로 만들지 않도록 했습니다(필요 시 별도 모니터링).

> **Q3-1. DB 는 살아 있는데 Redis 가 죽으면?**
> **A.** `ticketingDatastores` 가 **DOWN** 이면 Kubernetes/ALB 가 **트래픽을 끊을 수** 있습니다. 이 프로젝트는 Redis 없이는 대기열·홀드가 불가능하므로 **의도적으로 엄격**하게 둔 선택입니다. 운영 정책에 따라 Redis 만 분리 컴포넌트로 두는 것도 가능합니다.

---

### Q4. 배포 시 다운타임·롤링은?

**A.** **롤링 배포**(인스턴스 하나씩 새 버전으로 교체)를 가정합니다. 세션은 **Redis** 에 있어 **스티키 없이도** 재로그인 부담을 줄일 수 있습니다. **SSE** 는 로컬 메모리에 연결이 있어 **배포 중 끊김**이 생길 수 있어, 면접에서는 한계와 개선(스티키·Pub/Sub)을 말할 수 있으면 좋습니다.

> **Q4-1. DB 마이그레이션은?**
> **A.** Flyway 로 **버전드 마이그레이션**을 두고, 배포 전후 순서(호환 읽기 → 마이그레이션 → 신 코드)를 팀 규칙으로 맞춥니다.

---

### Q5. 로그·트레이싱은?

**A.** 구조화 로그(JSON)와 **요청·사용자·추적 ID(MdcFilter)** 로 상관 관계를 맞춥니다. 분산 트레이싱(OpenTelemetry 등)은 **다음 단계**로 두고, 현재는 로그+Mdc 로 최소 추적을 합니다.

> **Q5-1. PII 는 로그에 어떻게?**
> **A.** 카드 번호 전체·주민번호 등은 **남기지 않고**, `userId`·`reservationId` 같은 식별자만 남깁니다.
