# 아키텍처

## 시스템 구성도

```mermaid
flowchart TB
    subgraph client [Client Layer]
        Web[Static Web Pages<br/>HTML/CSS/JS]
        SSE[EventSource<br/>SSE Client]
    end

    subgraph api [Spring Boot Application]
        subgraph security [Security Layer]
            Sec[Spring Security<br/>인증/인가]
        end
        
        subgraph controllers [Controller Layer]
            AuthCtrl[AuthApiController]
            ConcertCtrl[ConcertController]
            QueueCtrl[QueueController]
            SeatCtrl[SeatController]
            HoldCtrl[HoldController]
            ResvCtrl[ReservationController]
            NotifCtrl[NotificationController]
            NotifSSE[NotificationSseController]
            MetricsCtrl[MetricsController]
        end
        
        subgraph services [Service Layer]
            AuthSvc[UsersService]
            ConcertSvc[ConcertService]
            QueueSvc[QueueService]
            SeatSvc[SeatService]
            HoldSvc[HoldService]
            ResvSvc[ReservationService]
            NotifSvc[NotificationService]
            SSENotifSvc[SseNotificationService]
            MetricsSvc[MetricsService]
            ActiveUser[ActiveUserTracker]
        end
        
        subgraph stores [Store Layer]
            HoldStore[HoldStore<br/>Redis 기반]
            LockSvc[RedisLockService<br/>분산 락]
        end
        
        subgraph schedulers [Scheduler Layer]
            QueueScheduler[QueueProcessingScheduler<br/>대기열 입장 허용 2초]
            QueueCleanup[QueueCleanupScheduler<br/>대기열 만료 토큰 정리 60초]
            HoldCleanup[HoldCleanupScheduler<br/>홀드 만료 정리 60초]
            RefundBatch[RefundForCancelledConcertScheduler<br/>취소 공연 환불 5분]
        end
        
        subgraph events [Event Layer]
            EventPub[SeatHoldEventPublisher<br/>Kafka Producer]
            EventCon[SeatHoldEventConsumer<br/>Kafka Consumer]
        end
    end

    subgraph data [Data Layer]
        MySQL[(MySQL<br/>영구 데이터)]
        Redis[(Redis<br/>세션/홀드/락/캐시/대기열)]
    end

    subgraph stream [Streaming Layer]
        Kafka[Apache Kafka<br/>이벤트 스트리밍]
    end

    Web --> Sec
    SSE --> Sec
    Sec --> controllers
    controllers --> services
    services --> MySQL
    services --> Redis
    services --> stores
    stores --> Redis
    services --> EventPub
    EventPub --> Kafka
    Kafka --> EventCon
    EventCon --> NotifSvc
    EventCon --> SSENotifSvc
    SSENotifSvc --> SSE
    schedulers --> services
    schedulers --> EventPub
```

## 레이어 요약

| 레이어 | 핵심 역할 | 비고 |
|--------|-----------|------|
| **Security** | Spring Security 세션 기반 인증/인가 | Redis 세션 저장 |
| **Controller** | REST API + SSE 엔드포인트 | `ApiResponse` 공통 래핑 |
| **Service** | 도메인별 비즈니스 로직, `@Transactional`, `@Cacheable` | 트랜잭션 경계 관리 |
| **Store** | Redis 기반 홀드 저장소 + 분산 락 | Lua 스크립트 원자성 보장 |
| **Scheduler** | 대기열 처리, 홀드/토큰 정리, 환불 배치 | 4종, 주기 설정 가능 |
| **Event** | Kafka Producer/Consumer | 알림·SSE 비동기 전달 |

## 핵심 예매 플로우 (End-to-End)

대기열 진입부터 예약 확정까지의 전체 흐름을 하나의 시퀀스로 표현한다.

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
    CONCERT ||--o{ SEAT : has
    CONCERT ||--o{ RESERVATION : reserves
    SEAT ||--o{ RESERVATION : reserves
    USERS ||--o{ RESERVATION : makes

    CONCERT {
        BIGINT id PK
        STRING title
        STRING venue
        DATETIME concert_at
        STRING status
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
    
    USERS {
        BIGINT id PK
        STRING username UK
        STRING pw
        DATETIME created_at
    }
```

`payment` 테이블: `payment_key`(UUID), `hold_token`, `user_id`, `concert_id`, `seat_id`, `amount`, `status`(READY/APPROVED/COMPLETED/CANCELED), `payment_method`(POINT/CARD), `reservation_id`, 타임스탬프 컬럼들.

## 확장성

| 항목 | 전략 |
|------|------|
| **세션/홀드/대기열/알림** | Redis 기반 → 앱 수평 확장 시 상태 공유 |
| **SSE 연결** | 인스턴스별 관리 → 로드밸런서 Sticky Session 필요 |
| **Kafka Consumer** | Consumer Group 자동 분산 |
| **스케줄러** | 다중 인스턴스 시 분산 락으로 중복 실행 방지 |
