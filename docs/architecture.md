# 아키텍처 개요

## 시스템 구성

```
┌─────────────┐     ┌─────────────────────────────────┐
│   Client     │     │         ALB (추후 적용)           │
│  (Browser)   │────▶│   Sticky Session (JSESSIONID)    │
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
| Language | Java 21 | LTS, Virtual Thread 적용 ([ADR-005](adr/005-virtual-threads.md)) |
| Framework | Spring Boot 3.4.1 | Web, Security, Data JPA, Kafka |
| Database | MySQL 8.0 | 콘서트/좌석/예약/결제 영속 데이터 |
| Cache/Lock | Redis 7 | 세션, 분산 락, 좌석 홀드, 대기열, 캐시, 알림 |
| Message Queue | Kafka | 이벤트 드리븐: 홀드 이벤트, 결제 완료 알림 |
| Migration | Flyway | DB 스키마 버전 관리 |
| Monitoring | Prometheus + Grafana | 비즈니스/인프라 메트릭, 대시보드 |
| Load Test | k6 | 부하 테스트, Knee Point 측정 |
| API Doc | SpringDoc (Swagger) | OpenAPI 3.0 자동 문서화 |
| Resilience | Resilience4j | Redis 서킷브레이커 |

## 핵심 설계 원칙

### 0. Virtual Thread (Java 21)
- **Tomcat 요청 스레드**를 Virtual Thread로 전환 → Platform Thread 200개 한계 제거
- **배치 스케줄러** 내부 I/O 작업을 Virtual Thread로 병렬 처리 (홀드 정리, 환불)
- **Kafka Consumer 리스너**도 Virtual Thread에서 실행 → DB/이메일 I/O 대기 중 OS 스레드 미점유
- CPU-bound이거나 라이브러리 내부 스레드(Netty, Kafka Producer)는 의도적으로 제외 → [ADR-005](adr/005-virtual-threads.md)

### 1. 동시성 제어 (좌석 선점)
- **Redis 분산 락** (SETNX + TTL + Lua 해제)으로 좌석 단위 배타적 잠금
- **Redis Lua 스크립트**로 홀드 생성/해제를 원자적 처리
- **DB 비관적 락** (PESSIMISTIC_WRITE)으로 결제/포인트 정합성 보장

### 2. 이벤트 드리븐 아키텍처
- 결제 완료 → Kafka → 이메일/SMS 비동기 알림
- 홀드 만료/예약 확정 → Kafka → SSE + Redis 알림
- **DLQ(Dead Letter Queue)**: 처리 실패 메시지 → `*.DLT` 토픽으로 전송, 수동 재처리

### 3. 장애 대응
- **멱등성 키**: 결제 API에 `Idempotency-Key` 헤더로 중복 방지
- **보상 트랜잭션 (Saga)**: 예약 확정 실패 시 결제 승인 되돌림
- **서킷브레이커**: Redis 장애 시 빠른 실패 전환
- **Rate Limiting**: Redis Sliding Window로 API 남용 방지

### 4. 관측성 (Observability)
- Prometheus 커스텀 메트릭: 활성 홀드 수, 선점 성공률, 결제 완료 소요 시간
- 구조화 로깅 (JSON): 운영 환경에서 ELK/Loki 연동 대비
- 커스텀 헬스 체크: Redis, Kafka, DB 상태 모니터링

## 예매 흐름

```
사용자 → 대기열 진입 → 좌석 선택 → 홀드(선점) → 결제 요청 → 결제 승인 → 결제 완료(예약 확정)
                                    │                                      │
                                    │ Redis 분산 락                         │ DB 트랜잭션
                                    │ + Lua 원자적 생성                     │ + 보상 트랜잭션
                                    │                                      │
                                    └── 홀드 만료 시 자동 해제 ──────────────┘
```
