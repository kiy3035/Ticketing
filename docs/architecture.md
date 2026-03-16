# 아키텍처

## 인프라 구성도

```mermaid
flowchart LR
    subgraph user [사용자]
        Client[Browser]
    end

    subgraph aws [AWS]
        ALB[ALB<br/>Application Load Balancer]

        subgraph app1 [App Server 1 — t3.small]
            Docker1[Docker]
            Boot1[Spring Boot<br/>Java 21]
        end

        subgraph app2 [App Server 2 — t3.small]
            Docker2[Docker]
            Boot2[Spring Boot<br/>Java 21]
        end

        subgraph infra [Infra Server — t3a.medium]
            DockerInfra[Docker Compose]
            Redis[(Redis)]
            Kafka[Kafka + Zookeeper]
            Prometheus[Prometheus]
            Grafana[Grafana]
        end

        RDS[(Amazon RDS<br/>MySQL)]

        subgraph k6srv [k6 Server]
            K6[k6 부하 테스트]
        end
    end

    subgraph cicd [CI/CD]
        GH[GitHub]
        GHA[GitHub Actions]
    end

    Client --> ALB
    ALB --> Boot1
    ALB --> Boot2
    Boot1 --> RDS
    Boot2 --> RDS
    Boot1 --> Redis
    Boot2 --> Redis
    Boot1 --> Kafka
    Boot2 --> Kafka
    Prometheus --> Boot1
    Prometheus --> Boot2
    Grafana --> Prometheus
    K6 --> ALB

    GH --> GHA
    GHA -->|deploy| Boot1
    GHA -->|deploy| Boot2
```

| 구성 요소 | 스펙 | 용도 |
|-----------|------|------|
| **App Server x2** | t3.small | Spring Boot 애플리케이션 (Docker) |
| **Infra Server** | t3a.medium | Redis, Kafka, Prometheus, Grafana (Docker Compose) |
| **RDS** | MySQL | 영구 데이터 (공연, 좌석, 예약, 결제, 사용자) |
| **ALB** | — | 트래픽 분산, 헬스체크 (`/actuator/health`) |
| **k6 Server** | — | 부하 테스트 실행 |
| **GitHub Actions** | — | main push → 빌드 → EC2 배포 |

## 애플리케이션 레이어 구조

```mermaid
flowchart TB
    Controller["Controller Layer<br/>(REST API + SSE)"]
    Security["Spring Security<br/>(Redis Session 인증)"]
    Service["Service Layer<br/>(비즈니스 로직, @Transactional)"]
    Store["Store Layer<br/>(HoldStore · RedisLockService)"]
    Scheduler["Scheduler Layer<br/>(대기열 처리 · 홀드 정리 · 환불 배치)"]
    Event["Event Layer<br/>(Kafka Producer/Consumer)"]
    MySQL[(MySQL)]
    Redis[(Redis)]
    Kafka[Kafka]

    Controller --> Security --> Service
    Service --> Store
    Service --> MySQL
    Store --> Redis
    Scheduler --> Service
    Scheduler --> Store
    Event --> Kafka
    Service --> Event
```

| 레이어 | 역할 | 비고 |
|--------|------|------|
| **Security** | 세션 기반 인증/인가 | Redis 세션 저장 |
| **Controller** | REST API + SSE 엔드포인트 | `ApiResponse` 공통 래핑 |
| **Service** | 도메인 비즈니스 로직 | `@Transactional`, `@Cacheable` |
| **Store** | Redis 기반 홀드 저장소 + 분산 락 | Lua 스크립트 원자성 |
| **Scheduler** | 대기열·홀드·토큰 정리, 환불 배치 | 4종, 분산 락으로 중복 방지 |
| **Event** | Kafka Producer/Consumer | 알림·SSE 비동기 전달 |

## 핵심 예매 플로우 (End-to-End)

```mermaid
sequenceDiagram
    participant U as User
    participant API as Spring Boot
    participant R as Redis
    participant SCH as Schedulers
    participant DB as MySQL
    participant K as Kafka

    Note over U,K: 1. 대기열 진입
    U->>API: POST /api/queue/enter
    API->>R: ZADD queue:concert:{id} (ZSet)
    API->>R: SET queue:token:{token} EX 1800
    API-->>U: token, rank

    Note over U,K: 2. 입장 허용 (스케줄러 2초 주기)
    SCH->>R: ZRANGE 상위 N명 조회
    SCH->>R: SET queue:allowed:{token}
    U->>API: GET /api/queue/status (폴링)
    API-->>U: isAllowed=true → 좌석 페이지 이동

    Note over U,K: 3. 좌석 홀드
    U->>API: POST /api/holds
    API->>R: SETNX lock:seat:{seatId} (분산 락)
    API->>R: Lua Script (홀드 원자적 생성)
    API->>K: publish(HOLD_CREATED)
    API-->>U: holdToken, expiresAt

    Note over U,K: 4. 결제 (READY → APPROVED → COMPLETED)
    U->>API: POST /api/payments/request
    API->>DB: Payment READY 생성
    U->>API: POST /api/payments/{key}/approve
    API->>DB: 포인트 차감, APPROVED
    U->>API: POST /api/payments/{key}/complete
    API->>DB: 좌석 RESERVED, 예약 생성, COMPLETED

    Note over U,K: 5. 예약 확정 후처리 (DB 커밋 후)
    API->>R: 홀드 해제 (Lua Script)
    API->>K: publish(RESERVATION_CONFIRMED)
    K->>API: Consumer → 알림 저장 + SSE 전송
```

## 기술적 의사결정

| 기술 | 선택 이유 |
|------|----------|
| **Redis ZSet** (대기열) | RANK O(log N) 순번 조회, 타임스탬프 기준 자동 정렬 |
| **Redis 분산 락** | Lua 스크립트로 원자적 lock/unlock, 토큰 검증으로 안전한 해제 |
| **Kafka** | 이벤트 발행/소비 분리, Consumer Group 분산, 재처리 가능 |
| **SSE** | 서버→클라이언트 단방향 푸시, WebSocket보다 구현 간단, 자동 재연결 |
| **Lua 스크립트** (홀드) | 홀드 생성/해제 시 다중 키 원자적 연산 (EXISTS→SET→ZADD) |
| **AFTER_COMMIT 리스너** | DB 커밋 후 Redis 홀드 해제·Kafka 발행으로 롤백 시 불일치 방지 |
| **Google SMTP / Solapi** | 결제 완료 알림: 이메일(SMTP) 또는 SMS(Solapi), Kafka 비동기 처리 |

## ERD

```mermaid
erDiagram
    USERS ||--o{ RESERVATION : makes
    USERS ||--o{ PAYMENT : pays
    CONCERT ||--o{ SEAT : has
    CONCERT ||--o{ RESERVATION : reserves
    CONCERT ||--o{ PAYMENT : "결제 대상"
    SEAT ||--o| RESERVATION : reserves
    SEAT ||--o| PAYMENT : "결제 좌석"
    RESERVATION ||--o| PAYMENT : "결제 연결"

    USERS {
        BIGINT id PK
        STRING username UK
        STRING pw
        STRING email
        STRING phone
        STRING noti_type
        STRING role
        BIGINT point
        DATETIME created_at
    }

    CONCERT {
        BIGINT id PK
        STRING title
        STRING venue
        INSTANT concert_at
        ENUM status
        ENUM category
        BIGINT seller_id FK
        DATETIME created_at
    }

    SEAT {
        BIGINT id PK
        BIGINT concert_id FK
        STRING section
        STRING seat_no
        BIGINT price
        ENUM status
    }

    RESERVATION {
        BIGINT id PK
        BIGINT concert_id FK
        BIGINT seat_id FK
        STRING user_id
        ENUM status
        DATETIME reserved_at
    }

    PAYMENT {
        BIGINT id PK
        STRING payment_key UK
        STRING hold_token UK
        STRING user_id
        BIGINT concert_id FK
        BIGINT seat_id FK
        LONG amount
        ENUM payment_method
        STRING order_id
        STRING toss_payment_key
        ENUM status
        BIGINT reservation_id FK
        DATETIME approved_at
        DATETIME completed_at
        DATETIME canceled_at
        DATETIME created_at
    }
```

## 확장성

| 항목 | 전략 |
|------|------|
| **세션/홀드/대기열/알림** | Redis 기반 → 앱 수평 확장 시 상태 공유 |
| **SSE 연결** | 인스턴스별 관리 → 로드밸런서 Sticky Session 필요 |
| **Kafka Consumer** | Consumer Group 자동 분산 |
| **스케줄러** | 다중 인스턴스 시 분산 락으로 중복 실행 방지 |
