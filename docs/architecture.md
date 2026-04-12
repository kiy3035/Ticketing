# 아키텍처 개요

권장 읽기 순서: 본 문서 → [시퀀스·정합성 §5](sequence-diagrams.md#consistency-failure-scenarios) → [기술 결정 요약](decisions.md) → [API](api.md).

## 시스템 구성

```
┌─────────────┐     ┌─────────────────────────────────┐
│   Client     │     │         ALB (추후 적용)           │
│  (Browser)   │────▶│   JWT (Bearer + X-Refresh-Token)│
└─────────────┘     └───────────┬───────────────────────┘
                                │
                    ┌───────────┴───────────┐
                    ▼                       ▼
            ┌──────────────┐       ┌──────────────┐
            │  App Server  │       │  App Server  │
            │  (t3.small)  │       │  (t3.small)  │
            │  Spring Boot │       │  Spring Boot │
            │  Java 21     │       │  Java 21     │
            └──────┬───────┘       └──────┬───────┘
                   │                      │
                   └──────────┬───────────┘
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
       ┌────────────┐ ┌────────────┐ ┌──────────────┐
       │   MySQL    │ │   Redis    │ │    Kafka     │
       │  (RDS)     │ │ (세션/락/  │ │ (이벤트)     │
       │            │ │  캐시/홀드 │ │              │
       └────────────┘ │  /대기열)  │ └──────────────┘
                      └────────────┘
                      
       ┌────────────┐ ┌────────────┐
       │ Prometheus │ │  Grafana   │
       │ (메트릭)   │ │ (대시보드) │
       └────────────┘ └────────────┘

       Infra Server (t3a.medium) 1대에서 Redis, Kafka, Prometheus, Grafana 운영
       k6 Server (t3a.small) 1대에서 부하 테스트 실행
```

## 기술 스택

| 구분 | 기술 | 용도 |
|------|------|------|
| Language | Java 21 | LTS, Virtual Thread ([decisions §5](decisions.md#adr-virtual-threads)) |
| Framework | Spring Boot 3.4.1 | Web, Security, Data JPA, Kafka |
| Database | MySQL 8.0 | 콘서트/좌석/예약/결제 영속 데이터 |
| Cache/Lock | Redis 7 | JWT Access 블랙리스트, 분산 락, 좌석 홀드, 대기열, 캐시, 알림 |
| Message Queue | Kafka | 이벤트 드리븐: 홀드 이벤트, 결제 완료 알림 |
| Migration | Flyway | DB 스키마 버전 관리 |
| Monitoring | Prometheus + Grafana | 비즈니스/인프라 메트릭, 대시보드 |
| Load Test | k6 | 부하 테스트, Knee Point 측정 |
| API Doc | SpringDoc (Swagger) | OpenAPI 3.0 자동 문서화 |
| Resilience | Resilience4j | Redis 서킷브레이커 |

## 핵심 설계 원칙

### 0. Virtual Thread (Java 21)

- **Tomcat 요청 스레드**를 Virtual Thread로 전환 → Platform Thread 200개 한계 완화.
- **배치** 일부·**Kafka Consumer**에서 I/O 병렬 시 가상 스레드 활용.
- CPU-bound·Netty/Kafka Producer 내부는 제외 → [decisions §5](decisions.md#adr-virtual-threads).

<a id="distributed-lock"></a>

### 1. 동시성 제어 (좌석 선점)

- **Redis 분산 락** (SETNX + TTL + Lua 해제)으로 좌석 단위 배타적 잠금.
- **Redis Lua**로 홀드 생성/해제 원자적 처리.
- **DB 비관적 락**으로 결제·포인트 정합성 → [decisions §3](decisions.md#adr-pessimistic).

#### 락 키·사용 위치·설정

| 항목 | 내용 |
|------|------|
| 키 | `lock:seat:{seatId}` (값: UUID, 해제 시 본인 검증) |
| TTL | `ticketing.lock.ttl-seconds` (기본 5초) |
| 사용 | `HoldService.createHold`, `ReservationService.confirm` — 처리 후 즉시 unlock |
| Lua | GET+DEL 원자 실행, 토큰 일치할 때만 삭제 |
| 재시도 | `ticketing.lock.retry-count`, `retry-delay-ms` — 초과 시 `429 Too Many Requests` |

### 2. 이벤트 드리븐 아키텍처

- 결제 완료 → Kafka → 이메일/SMS 비동기.
- 홀드/만료 → Kafka → SSE·알림.
- **`RESERVATION_CONFIRMED`**: DB **transactional outbox** + 스케줄러 발행(예약 커밋과 동일 트랜잭션). 그 외 일부 이벤트는 직접 send → [sequence-diagrams §5](sequence-diagrams.md#consistency-failure-scenarios).
- **DLQ**: 실패 메시지 `*.DLT`, 수동 재처리.
- 배경: [decisions §2](decisions.md#adr-kafka).

### 3. 장애 대응

- **멱등성 키**: 결제 API `Idempotency-Key` → [decisions §4](decisions.md#adr-idempotency).
- **보상 트랜잭션**: 예약 확정 실패 시 결제 승인 되돌림(포인트 등).
- **서킷브레이커·Rate Limit**: Redis 장애·남용 대응.

### 4. 관측성

- Prometheus 커스텀 메트릭, JSON 로깅.
- Actuator: `ticketingDatastores` 헬스(Redis PING + DB `isValid`). Kafka는 기본 헬스 비활성(타임아웃 방지) — [infra.md](infra.md) 참고.

<a id="payment"></a>

## 결제·외부 연동

| 수단 | 요약 |
|------|------|
| **POINT** | 회원 포인트 차감, 가입 시 보너스. |
| **CARD** | 토스페이먼츠 **주문서형 위젯** 샌드박스. 서버는 `TossPaymentsClient`로 confirm. `TOSS_CLIENT_KEY` / `TOSS_SECRET_KEY`. |
| 플로우 | `POST .../request` → `approve` → `complete`(예약 확정). 상세는 [api.md](api.md). |
| 취소 | 포인트는 환불 처리. 카드는 샌드박스 범위 내 PG 취소 API 미연동 구간 있음. |

<a id="jwt-auth"></a>

## 인증 (JWT)

- **Access Token**(30분) + **Refresh Token**(14일), HS256. 상세·4가지 재발급 경우 → [jwt-auth.md](jwt-auth.md).
- Access **jti** 로그아웃 시 **Redis 블랙리스트**, Refresh **jti** 는 DB `refresh_tokens` 에서 revoke.
- 정적 HTML은 공개, `/api/**`(예외: 로그인·회원가입·대기열 등)는 JWT 필수.

## 예매 흐름

```
사용자 → 대기열 진입 → 좌석 선택 → 홀드(선점) → 결제 요청 → 결제 승인 → 결제 완료(예약 확정)
                                    │                                      │
                                    │ Redis 분산 락                         │ DB 트랜잭션
                                    │ + Lua 원자적 생성                     │ + outbox·보상
                                    │                                      │
                                    └── 홀드 만료 시 자동 해제 ──────────────┘
```

시퀀스 다이어그램: [sequence-diagrams.md](sequence-diagrams.md).
