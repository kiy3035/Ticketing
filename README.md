# Ticketing

콘서트 예매 백엔드(Spring Boot + JPA + MySQL + Redis)와 로그인 기반 프론트를 포함한 MVP 스켈레톤입니다.

## 흐름도

```mermaid
flowchart TD
    A[브라우저] -->|로그인/회원가입| B[Spring Security]
    B -->|인증 성공| C[템플릿 화면]
    C -->|API 요청| D[REST API]
    D --> E[서비스]
    E --> F[(MySQL)]
    E --> G[(Redis)]
    G -->|캐시/락| E
    E --> H[스케줄러]
    H -->|만료 홀드 정리| F
```

## 아키텍처

```mermaid
flowchart LR
    FE[정적/템플릿 프론트] --> API[Spring Boot API]
    API --> JPA[JPA/Hibernate]
    JPA --> DB[(MySQL)]
    API --> R[(Redis)]
    API --> S[스케줄러]
    S --> DB
```

### 주요 컴포넌트
- **API/서비스**: 콘서트/좌석 조회, 홀드, 예약 확정
- **MySQL**: `concert`, `seat`, `seat_hold`, `reservation`, `user_account`
- **Redis**: 캐시(콘서트/좌석), 좌석 락, 대기열 토큰(확장)
- **스케줄러**: 만료된 홀드 정리
- **보안**: 폼 로그인 기반 인증

## ERD(초안)

```mermaid
erDiagram
    CONCERT ||--o{ SEAT : has
    CONCERT ||--o{ SEAT_HOLD : holds
    CONCERT ||--o{ RESERVATION : reserves
    SEAT ||--o{ SEAT_HOLD : holds
    SEAT ||--o{ RESERVATION : reserves
    USER_ACCOUNT ||--o{ SEAT_HOLD : creates
    USER_ACCOUNT ||--o{ RESERVATION : makes

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
    SEAT_HOLD {
        BIGINT id PK
        BIGINT concert_id FK
        BIGINT seat_id FK
        STRING user_id
        STRING hold_token
        DATETIME expires_at
        DATETIME created_at
    }
    RESERVATION {
        BIGINT id PK
        BIGINT concert_id FK
        BIGINT seat_id FK
        STRING user_id
        STRING status
        DATETIME reserved_at
    }
    USER_ACCOUNT {
        BIGINT id PK
        STRING username
        STRING password_hash
        DATETIME created_at
    }
```

## 실행 흐름 요약
1. 로그인/회원가입 후 `/app` 접근
2. `/api/concerts`로 콘서트 목록 조회(캐시 사용)
3. `/api/concerts/{id}/seats`로 좌석 조회(캐시 사용)
4. `/api/holds`로 좌석 홀드(Redis 락 적용)
5. `/api/reservations`로 예약 확정
6. 스케줄러가 만료된 홀드를 정리
