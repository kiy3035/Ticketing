# 아키텍처 & 플로우

## 아키텍처 개요

```mermaid
flowchart LR
    subgraph client [Client]
        Web[Static Web]
    end

    subgraph api [Spring Boot API]
        Sec[Security]
        Ctrl[Controllers]
        Svc[Services]
        Sch[Schedulers]
        Con[Kafka Consumers]
    end

    subgraph data [Data]
        DB[(MySQL)]
        R[(Redis)]
    end

    subgraph stream [Streaming]
        K[Kafka]
    end

    Web --> Ctrl
    Sec --> Ctrl
    Ctrl --> Svc
    Svc --> DB
    Svc --> R
    Svc --> K
    Sch --> K
    K --> Con
    Con --> R
```

## 핵심 흐름 (Hold 만료 알림)

```mermaid
sequenceDiagram
    participant U as User
    participant FE as Frontend
    participant API as API
    participant R as Redis
    participant K as Kafka
    participant SCH as Scheduler

    U->>FE: 좌석 선택 후 예매하기
    FE->>API: POST /api/holds
    API->>R: hold:seat, hold:token, hold:expires (TTL)
    API->>K: HOLD_CREATED

    SCH->>R: 만료 홀드 스캔
    SCH->>K: HOLD_EXPIRED
    K->>API: Consumer 수신
    API->>R: notify:user:{userId}
    FE->>API: GET /api/notifications
```

## 핵심 플로우
1. 로그인/회원가입 후 `/app.html` 접근
2. 콘서트 목록 조회: `GET /api/concerts`
3. 좌석 조회: `GET /api/concerts/{id}/seats` (DB 예약 + Redis 홀드 오버레이)
4. 홀드 생성: `POST /api/holds` (Redis TTL)
5. 예약 확정: `POST /api/reservations` (DB 기록 + Redis 홀드 제거)
6. 만료 홀드 스캔 → `HOLD_EXPIRED` 이벤트 발행
7. 알림 소비자가 이벤트 수신 → Redis 알림 저장

## ERD(초안)

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
        DATETIME start_at
        DATETIME end_at
        STRING status
        DATETIME created_at
    }
    SEAT {
        BIGINT id PK
        BIGINT concert_id FK
        STRING section
        STRING seat_no
        BIGINT price
        STRING status
    }
    RESERVATION {
        BIGINT id PK
        BIGINT concert_id FK
        BIGINT seat_id FK
        STRING user_id
        STRING status
        DATETIME reserved_at
    }
    USERS {
        BIGINT id PK
        STRING username
        STRING pw
        DATETIME created_at
    }
```
