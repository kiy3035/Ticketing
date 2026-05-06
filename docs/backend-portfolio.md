# 콘서트 티켓 예매 시스템 — 백엔드 포트폴리오

> **한 줄 요약**: 동시 1,000명 이상 접속 환경에서 좌석 중복 예약 0건을 보장하는 콘서트 예매 백엔드.
> Redis 분산 락 · Kafka 이벤트 · Transactional Outbox · Saga 보상 패턴을 직접 구현했고, **부하 테스트로 병목을 진단해 p95를 2.06s → 164ms로 92% 단축**한 트러블슈팅 사례를 보유합니다.

---

## 가장 자랑하고 싶은 3가지

| # | 항목 | 결과 |
|---|------|------|
| 1 | **DB 병목 진단 → 캐시 도입** | p95 2.06s → 444ms (▼78%), RPS 376 → 834/s (▲122%). 풀 크기·Virtual Thread 변경으로 안 풀리던 병목을 폴링 쿼리 캐시화로 해결 |
| 2 | **분산 락 정확성 증명** | 100 VU 동시 선점 시 201 응답 정확히 1건. 2대 nginx 분산 환경에서도 동일 결과로 **좌석 중복 예약 0건 불변식** 검증 |
| 3 | **스케일아웃 + knee point 측정** | 앱 서버 1→2대로 RPS 834→1447/s (▲73%), p95 ▼63%. VU=1500에서 처리량이 오히려 감소하는 knee point를 시나리오 기반으로 탐지 |

---

## 목차

1. [프로젝트 개요](#1-프로젝트-개요)
2. [기술 스택 & 선정 이유](#2-기술-스택--선정-이유)
3. [시스템 아키텍처](#3-시스템-아키텍처)
4. [트러블슈팅 사례 (5개)](#4-트러블슈팅-사례) ← **핵심**
5. [기술적 의사결정 (ADR)](#5-기술적-의사결정-adr)
6. [부하 테스트 핵심 결과](#6-부하-테스트-핵심-결과)
7. [관측성](#7-관측성)
8. [프로젝트 구조](#8-프로젝트-구조)

---

## 1. 프로젝트 개요

| 항목 | 내용 |
|------|------|
| **목적** | 대규모 트래픽 처리·좌석 동시 선점 제어·인프라 운영 경험을 포트폴리오로 증명 |
| **핵심 문제** | ① 공연 오픈 순간 트래픽 폭주 ② 동일 좌석 동시 선점 경쟁 ③ 결제·예약 정합성 보장 |
| **인프라** | t3a.medium(Redis/Kafka/Prometheus/Grafana/nginx) + t3.small 앱 서버 **2대** + t3.small k6 |
| **부하 검증** | 안정 운영 상한 VU=800 (1,447 RPS, 에러 0%), Knee Point VU=1,000~1,200 |

### 주요 구현
- JWT Access/Refresh 인증 (Redis Access 블랙리스트 + DB Refresh `revoked` 마킹)
- Redis ZSet 대기열 + 즉시 입장 임계값
- Redis 분산 락(SETNX + Lua) + Lua 원자적 좌석 홀드
- 3단계 결제 플로우 (READY → APPROVED → COMPLETED)
- Saga 보상 트랜잭션 (`REQUIRES_NEW` 분리 커밋)
- Transactional Outbox (예약 확정 이벤트 유실 방지)
- Resilience4j 서킷브레이커 + Sliding Window Rate Limit
- Prometheus 커스텀 메트릭 + Grafana 대시보드

---

## 2. 기술 스택 & 선정 이유

| 구분 | 기술 | 선택 이유 |
|------|------|-----------|
| Language | **Java 21** | Virtual Thread로 I/O 대기 중 OS 스레드 점유 제거 (실측 JVM threads 225→30) |
| Framework | **Spring Boot 3.4** | Security/JPA/Kafka/Cache 통합, Virtual Thread 1행 활성화 |
| Database | **MySQL 8.0** | 결제·예약 영속, `SELECT ... FOR UPDATE`로 금전 정합성 |
| Cache/Lock | **Redis 7** | 분산 락(SETNX), 좌석 홀드, 대기열(ZSet), JWT 블랙리스트, 잔여석 캐시 |
| MQ | **Apache Kafka** | 결제 알림·홀드 이벤트 비동기 처리, DLT 재처리, `acks=all`+`idempotence=true` |
| Migration | **Flyway** | `ddl-auto=validate`로 환경 간 스키마 드리프트 방지 |
| Monitoring | **Prometheus + Grafana** | 비즈니스 메트릭 + Golden Signals 기반 대시보드 |
| Load Test | **k6** | Knee Point 측정, 스테이지 시나리오 |
| Resilience | **Resilience4j** | Redis 서킷브레이커 — 장애 시 fast-fail + fallback |

---

## 3. 시스템 아키텍처

```
┌──────────────────────────────────────────────────────────────────┐
│                          Client (Browser)                        │
└──────────────────────────┬───────────────────────────────────────┘
                           │  HTTP  (JWT Bearer + X-Refresh-Token)
                           ▼
               ┌───────────────────────┐
               │  nginx (인프라 서버)   │  ← :80, least_conn
               └──────────┬────────────┘
              ┌───────────┴────────────┐
              ▼                        ▼
   ┌─────────────────────┐  ┌─────────────────────┐
   │   App Server #1     │  │   App Server #2     │
   │   t3.small          │  │   t3.small          │
   │   Spring Boot 3.4   │  │   Spring Boot 3.4   │
   │   Java 21 (Loom VT) │  │   Java 21 (Loom VT) │
   └──────────┬──────────┘  └──────────┬──────────┘
              └─────────────┬───────────┘
                            │
          ┌─────────────────┼──────────────────────┐
          ▼                 ▼                      ▼
  ┌──────────────┐  ┌──────────────────┐   ┌──────────────┐
  │  MySQL 8.0   │  │    Redis 7       │   │    Kafka     │
  │  · 예약/결제 │  │  · 분산 락        │   │  · 홀드 이벤트│
  │  · 아웃박스  │  │  · 좌석 홀드      │   │  · 결제 완료 │
  │              │  │  · 대기열 ZSet    │   │  · DLT       │
  │              │  │  · JWT 블랙리스트 │   │              │
  │              │  │  · 잔여석 캐시    │   │              │
  └──────────────┘  └──────────────────┘   └──────────────┘
```

상세 시퀀스: [`sequence-diagrams.md`](sequence-diagrams.md) · Redis 키/Kafka 토픽: [`data.md`](data.md)

> **nginx `least_conn` 선택 이유**: 폴링성 트래픽(`/api/queue/status`)과 30분 long-lived SSE 연결, 외부 PG 호출이 섞인 처리 시간 편차가 큰 워크로드라 단순 카운트 기반 round-robin보다 활성 연결 수가 적은 서버에 우선 배정하는 `least_conn`이 자연스러운 부하 평형을 만든다. Phase 4 실측 분배 비율 app1:app2 = 50.9%:49.1%로 의도대로 동작 확인.

---

## 4. 트러블슈팅 사례

> 부하 테스트와 동시성 검증 과정에서 실제로 부딪힌 문제와 해결 과정입니다.
> 부록: [부하 테스트 상세 데이터](load-test-portfolio.md)

---

### 사례 1. "풀을 늘려도 p95가 안 떨어진다" — DB 병목의 진짜 원인

#### 상황
VU=800 부하에서 `GET /api/queue/status` 폴링 응답의 p95가 **1.93s**. Hikari `connections_active`가 10에 플랫하게 붙어있고 `pending`이 ~170까지 적체. "풀이 좁아서 그런 것 아니냐"는 직관적 가설로 시작했다.

#### 시도 1 — 풀을 10 → 30으로 확장 (실패)
가설: 풀이 3배가 되면 pending이 사라지고 p95도 떨어진다.
결과: **p95 1.93s → 1.85s, RPS 408 → 386/s.** 차이 0.08s. pending은 줄었으나 p95가 거의 그대로.

#### 시도 2 — Virtual Thread 도입 (실패)
가설: I/O 대기 중 OS 스레드를 안 잡으면 처리량이 오를 것이다.
결과: JVM live threads는 **225 → 30개로 87% 감소(이건 명확한 효과)**. 하지만 **p95 2.06s, RPS 376/s** — 처리량은 오히려 소폭 악화.

#### 진단 — 병목은 커넥션 수가 아니라 쿼리 빈도
세 변수(풀, 스레드 모델)를 모두 바꿨는데 p95가 1.8~2.1s 구간에서 수렴. 이 시점에 가설을 의심했다.
`/api/queue/status`는 매 폴링마다 DB 쿼리 3회(`countByConcertId`, `countByStatus`, `findSeatIds`)를 실행. VU=800이 5ms 간격으로 폴링하면 **초당 수천 건의 COUNT 쿼리**가 DB로 향한다.
→ 풀을 늘려도, 스레드를 가볍게 만들어도 **DB가 처리할 수 있는 쿼리 수 자체**가 한계.

#### 해결 — 잔여석을 Redis에 캐시 (TTL 2초)
잔여석 수는 1~2초 단위 근사값으로도 충분하다는 도메인 판단. `SeatService.countAvailableSeatsForQueueStatus()`에 `@Cacheable(TTL=2s)` 적용. 홀드 생성/해제, 예약 확정, 만료 정리 시 `@CacheEvict`로 무효화.

#### 결과

| 지표 | 캐시 전 | 캐시 후 | 변화 |
|------|---------|---------|------|
| p95 | 2.06s | **444ms** | ▼ 78% |
| RPS | 376/s | **834/s** | ▲ 122% |
| DB pending | 높음 | 거의 0 | - |

#### 회고
- 직관적 가설(풀 부족)을 빠르게 기각할 수 있었던 건 **Grafana에서 active/pending 패턴을 그래프로 본 덕분**이었다. 수치만 봤으면 가설을 더 오래 붙들고 있었을 것.
- "pending이 줄어도 p95가 안 줄면 그건 커넥션이 병목이 아니다"라는 해석이 핵심이었음.
- 캐시는 폴링성 GET에만 적용하고, 무효화 시점을 명확히 정의(홀드 생성/해제/만료/예약 확정)해야 정합성이 깨지지 않는다.

---

### 사례 2. "100명이 동시에 같은 좌석을 잡으면?" — 분산 락 불변식 증명

#### 상황
앱 서버 2대 + nginx 환경에서 동일 좌석에 동시 선점 요청이 들어올 때 **정확히 1명만 성공**해야 한다. 코드 리뷰만으로는 불충분하고 부하 테스트로 직접 증명하기로 함.

#### 설계 결정
- **Redis SETNX + UUID 토큰**으로 좌석당 배타적 락. TTL 3초 (보유자 죽어도 다음 요청이 빠르게 진입).
- **Lua 스크립트로 unlock** — `GET == 토큰 → DEL` 원자 처리. 다른 소유자의 락을 오삭제하는 사고 방지.
- **Lua로 holdInfo 작성** — `EXISTS` 확인 → 좌석→토큰 SET → 토큰→상세 SET → 만료 ZADD를 한 번에. 중간 끼어듦 차단.
- **Redisson 미사용** — 단일 Redis 인스턴스에서 Redlock은 과한 복잡도라고 판단. UUID 토큰 + Lua 해제만으로 충분.

#### 검증 — k6 `shared-iterations` (100 VU × 100 iter, 단일 좌석, 각 구성 10회 반복)

매 회차 전 `redis-cli FLUSHDB` + 좌석 DB 초기화(`status=AVAILABLE`, `seat_hold` 삭제). 1대 직접·2대 nginx 각각 10회씩 총 **20회 독립 시행**.

| 응답 | 1대 직접 (10회 합) | 2대 nginx (10회 합) |
|------|-----------------|-------------------|
| **201 선점 성공** | **모든 회차 1건** ✅ | **모든 회차 1건** ✅ |
| 5xx | 0건 | 0건 |
| 409 이미 선점 | 0~7건 (회차별 변동) | 0~21건 (회차별 변동) |
| 429 락 경합 | 92~99건 (회차별 변동) | 78~99건 (회차별 변동) |

**20회 독립 시행 전부 201=정확히 1건.** Redis가 단일 잠금 저장소이므로 요청이 nginx를 거쳐 2대에 분산되어도 락의 원자성이 유지됨을 통계적으로 증명. 409·429 비율은 회차마다 달라지지만 두 응답 모두 중복 선점을 막는 올바른 동작이다.

#### 부수 발견 — API 응답 코드 버그
초기 검증에서 `201 선점 성공` 체크가 0건으로 나왔다. 그런데 `http_req_failed`를 역산하면 1건은 비실패. 추적해보니 `HoldController`가 `ResponseEntity` 없이 `HoldResponse`를 직접 반환해 Spring이 기본 200 OK를 적용하고 있었다.
→ `@ResponseStatus(HttpStatus.CREATED)` 추가로 수정. 부하 테스트가 우연히 발견한 응답 코드 결함.

#### 회고
- "테스트가 통과하면 OK"가 아니라 **응답 코드 분포까지 셈해야 한다.** http_req_failed 역산이 없었으면 컨트롤러 버그를 못 잡았을 것.
- k6의 4xx 기본 실패 처리는 이 시나리오(409/429가 정상)에서는 false alarm. `http_req_failed: rate<1` 로 임계값 명시 억제.

---

### 사례 3. "결제는 됐는데 예약이 안 됐다" — Saga 보상 패턴

#### 상황
3단계 결제 플로우(READY → APPROVED → COMPLETED) 마지막 단계에서 예약 확정(`reservationService.confirm()`)이 실패할 수 있다. 홀드 만료, 좌석 경합, DB 일시 장애 등.
이 시점에서는 이미 포인트가 차감됐거나 카드 결제가 승인된 상태 — **돈은 빠졌는데 좌석은 없는 불일치** 위험.

#### 시도 — 단일 트랜잭션으로 감싸기 (부적절)
`completePayment` 전체를 한 트랜잭션으로 묶으면 예외 시 롤백되어 깔끔할 것 같았지만:
- 결제 승인은 **외부 PG(토스페이먼츠) 호출**이 포함되어 트랜잭션 안에 둘 수 없음 (네트워크 시간이 트랜잭션을 잡음).
- `users.point` 차감은 비관적 락이 필요 → 좌석 락과 트랜잭션 경계가 충돌.

#### 해결 — Saga 보상 트랜잭션 (REQUIRES_NEW)
- `PaymentCompensationService`를 별도 빈으로 분리, `@Transactional(propagation=REQUIRES_NEW)`.
- 예약 확정 예외 발생 시 보상 메서드를 호출 → **포인트 환불 + Payment.status=CANCELED**를 독립 트랜잭션으로 커밋.
- 보상 후 원래 예외를 re-throw → 클라이언트에 정확한 오류 응답.
- 좌석 락은 Redis 담당, 금전 정합성은 DB 비관적 락 담당으로 **역할 분리**.

#### 정합성 보장 포인트
| 시나리오 | 처리 |
|----------|------|
| 예약 확정 중 예외 | REQUIRES_NEW 보상 → 포인트 환불 + CANCELED |
| 결제 API 중복 요청 | `Idempotency-Key` + Redis TTL + `@Idempotent` AOP |
| Reservation 커밋 후 Kafka 발행 실패 | Outbox PENDING 유지 → 스케줄러 재시도 (최대 25회) |
| Redis `releaseHold` 실패 | DB 예약은 커밋, Redis 홀드는 TTL까지 잔존 후 자동 만료 |

#### 회고
- "단일 트랜잭션으로 다 묶기"는 외부 I/O가 끼면 안 통한다. **트랜잭션 경계는 비즈니스 단위가 아니라 정합성이 필요한 최소 단위**로 잘라야 한다.
- 보상 코드를 같은 클래스의 다른 메서드로 두면 self-invocation 문제(Spring AOP가 가로채지 못함)로 REQUIRES_NEW가 안 먹는다 → **별도 빈으로 분리**가 필수.

---

### 사례 4. "VU를 1.875배 늘렸는데 RPS가 줄었다" — Knee Point 탐지

#### 상황
앱 서버 2대 구성에서 VU=800 → 1500으로 올리자, 직관과 반대로:

| 구성 | VU | p95 | RPS | 에러율 |
|------|----|-----|-----|--------|
| 2대, VU=800 | 800 | 164ms | 1,447/s | 0% |
| **2대, VU=1500** | 1500 | **1.74s** | **1,177/s** | **3.41%** |

VU를 늘렸는데 처리량이 줄고 에러가 발생 — **시스템이 포화 상태에 진입했다는 전형적 신호**.

#### 진단 — Knee Point 탐지 시나리오 작성
"VU=800은 안전, VU=1500은 위험"만으로는 운영 SLO를 정할 수 없다. 정확한 변곡점이 필요.
`knee-point.js` 스크립트로 VU를 500→800→1000→1200→1500 단계 ramp.

#### 시행착오 3회
| 회차 | 문제 | 수정 |
|------|------|------|
| 1 | `queue-flow.js` stress profile 사용 — 단일 피크라 ramp가 아님 | `knee-point.js` 신규 작성 |
| 2 | 진입 실패 시 `sleep(1)` 후 즉시 재시도 → Rate Limiter 연쇄 (성공률 21%) | `sleep(5)`로 변경 |
| 3 | `MAX_POLLS=1000` (~4분 폴링) → 600+ VU 동시 폴링으로 5xx 발생 | `MAX_POLLS=300` (큐 드레인 60s 기준 + 여유) |

#### 결과 — Knee Point 식별
두 지표가 같은 구간을 가리켰다:
1. **k6 EOF 발생 시점** = 178초 = VU=1000→1200 전환 직후 (서버가 처음 연결을 끊기 시작)
2. **Grafana RPS 곡선** = 같은 구간에서 평탄화

| VU 구간 | 에러율 | 판정 |
|---------|--------|------|
| ≤ 800 | 0% | ✅ 안정 운영 |
| **1,000~1,200** | EOF 시작 | ⚠️ **Knee Point** |
| ≥ 1,500 | 3.41% | ❌ 한계 초과 |

→ **t3a.small 2대 + nginx 구성의 안정 처리 상한 VU=800 (~1,447 RPS)** 결론.

#### 회고
- "에러 없음"과 "지연이 짧음"은 별개 지표. p95=1.74s 자체는 봐줄 만해도 에러율 3.41%면 운영 불가. **SLO는 p95와 에러율을 별도 기준으로 잡아야 한다.**
- 부하 테스트 스크립트 자체도 두 번 갈아엎었음. 측정 도구의 행동(`sleep`, `MAX_POLLS`)이 결과에 미치는 영향을 통제하는 것도 부하 테스트의 일부.

---

### 사례 5. "로그아웃해도 Access가 살아있다" — JWT의 무상태성 약점 보완

#### 상황
JWT 인증을 도입할 때 두 가지 요구사항이 있었다.
1. **UX**: Access TTL을 짧게 잡으면 자주 재로그인하게 됨 → Refresh Token으로 자동 재발급 필요
2. **보안**: JWT는 서명만 유효하면 만료 전까지 사용 가능 → **로그아웃했는데도 탈취된 Access로 요청이 통과될 수 있음**

#### 해결 1 — Access + Refresh 두 토큰 구조

| 항목 | Access | Refresh |
|------|--------|---------|
| TTL | 30분 (짧게) | 14일 (길게) |
| 용도 | 매 요청 인증 | Access 만료 시 새 Access 발급 |
| 저장 | 클라이언트 메모리 | 클라이언트 + DB(`refresh_tokens`) |

매 요청에 Access + Refresh 둘 다 보내고, 만료 조합에 따라 4가지로 분기:

| 케이스 | Access | Refresh | 동작 |
|--------|--------|---------|------|
| 1 | 만료 | 만료 | 401 → 재로그인 |
| 2 | 만료 | 유효 | 새 Access 발급 (`X-New-Access-Token` 헤더) |
| 3 | 유효 | 만료 | Access 살아있으니 정상 통과 |
| 4 | 유효 | 유효 | 정상 처리 |

→ 별도 `/refresh` 엔드포인트로 빼면 클라이언트가 "401 받음 → refresh 호출 → 원래 요청 재시도" 3-step을 처리해야 하는데, 두 토큰 동반 전송으로 **서버가 투명하게 재발급**해 클라이언트 로직 단순화.

#### 해결 2 — 로그아웃 시 Access를 Redis 블랙리스트로 차단

**문제**: 로그아웃해도 Access JWT는 서명이 유효해서 만료 전까지 통과한다. 탈취된 Access가 있으면 사용자가 로그아웃해도 공격자는 계속 사용할 수 있다.

**해결**:
- 로그아웃 시 Access의 jti(UUID)를 **Redis에 블랙리스트로 등록** (`jwt:bl:{jti}`)
- 매 요청에서 Access jti가 블랙리스트에 있는지 확인 → 있으면 401
- TTL은 **Access의 잔여 유효 시간만큼만** 설정 → 만료되면 자동 삭제, Redis 메모리 낭비 없음
- Refresh는 DB에서 `revoked = true` 마킹

**왜 Access는 Redis, Refresh는 DB인가?**
- Access는 30분짜리라 TTL 기반 자동 만료가 적합 → Redis가 자연스러움
- Refresh는 14일짜리 + 사용자별 토큰 추적이 필요 → DB가 적합

#### 부수 결정 — 스케일아웃 환경에서의 무상태 인증

앱 서버가 2대로 늘어나도 JWT는 추가 작업 없이 동작한다.
- Access 검증은 **서명 확인만으로 완결** → 서버 간 세션 공유 불필요
- 상태가 필요한 부분(블랙리스트, refresh DB)은 **Redis와 MySQL을 공유** → 어느 서버로 라우팅돼도 동일한 결과
- 새 서버를 추가해도 동일한 `JWT_SECRET`만 주입하면 즉시 동작

#### 결과
- Access 30분 TTL을 유지하면서도 Refresh로 UX 확보
- 로그아웃 후 탈취된 Access는 만료 전까지도 차단됨 (Redis 블랙리스트 hit)
- 2대 스케일아웃 환경에서 별도 세션 동기화 없이 동작

#### 회고
- "JWT는 무상태"라는 말을 그대로 받아들이면 로그아웃 보안 구멍이 생긴다. **완전 무상태가 아니라, 무상태 + 최소한의 상태(블랙리스트, refresh DB) 조합**이 실무에 맞다.
- 매 요청에 두 토큰 동반 전송은 표준은 아니지만, 클라이언트 재시도 로직을 없애주는 효과가 컸음. 트레이드오프는 요청마다 토큰 검증 비용이 두 번이라는 점 — 부하 테스트(1,447 RPS)에서 문제 없음을 확인.

상세 설계: [`jwt-auth.md`](jwt-auth.md)

---

## 5. 기술적 의사결정 (ADR)

#### ADR-1. Redis SETNX 분산 락 (Redisson 미사용)
- 단일 Redis 인스턴스에서 Redlock은 과도. UUID 토큰 + Lua 해제로 동등한 안전성 확보
- TTL 3초 — 락 보유자가 죽어도 다음 요청이 빠르게 진입
- Sentinel/Cluster 전환 시 라이브러리 재검토 필요 (현재 단일 인스턴스 전제)

#### ADR-2. Kafka 이벤트 드리븐
- 결제 알림(이메일/SMS) 외부 I/O를 결제 API 응답 시간에서 분리
- `acks=all`, `idempotence=true`, `retries=3`으로 유실 최소화
- `RESERVATION_CONFIRMED`는 Outbox로 DB 정합성 강화, DLT로 수동 재처리

#### ADR-3. DB 비관적 락 (결제·포인트)
- 금전 이중 차감 방지: `SELECT ... FOR UPDATE`
- 좌석 선점은 Redis 락, 결제 정합성은 DB 락 — **자원별 락 역할 분리**

#### ADR-4. 멱등성 키 (AOP)
- `Idempotency-Key` 헤더 + Redis TTL + `@Idempotent` AOP
- 컨트롤러 어노테이션 한 줄로 비즈니스 로직과 완전 분리

#### ADR-5. Java 21 Virtual Thread
- Tomcat 요청 스레드 VT 전환 → JVM live threads **225→30** (87% 감소)
- I/O 바운드 환경에서만 효과 — DB 쿼리 빈도가 병목이면 처리량 개선은 제한적 (사례 1 참조)
- 제외 영역: 스케줄러 트리거, Netty/Kafka Producer 내부

---

## 6. 부하 테스트 핵심 결과

| 구성 | VU | p95 | RPS | 에러율 |
|------|----|-----|-----|--------|
| 1대, pool=10 (기준선) | 800 | 1.93s | 408/s | 0% |
| 1대, pool=30 + VT | 800 | 2.06s | 376/s | 0% |
| **1대, pool=30 + VT + 잔여석 캐시** | 800 | **444ms** | **834/s** | **0%** |
| **2대, nginx + 동일 구성** | 800 | **164ms** | **1,447/s** | **0%** |
| 2대, VU=1500 (한계 부하) | 1500 | 1.74s | 1,177/s | 3.41% |
| 동시 선점 정확성 (VU=100, 20회 시행) | - | - | - | 201: **모든 회차 1건** |
| **2대, app1 30s 다운 (페일오버)** | 800 | 246ms | - | **20.77%** |
| 2대, 페일오버 + nginx 튜닝 + 클라이언트 retry | 800 | 180ms | - | **11.05%** |

> 페일오버 에러율 ~20%는 nginx passive health check(`max_fails=2 fail_timeout=10s`)의 구조적 결과 — kill 후 ~10초 격리 지연 동안 사용자 에러 발생. 두 회차 0.06%p 차이로 재현.
>
> **개선 시도 (ablation)**: nginx 튜닝(`max_fails=1`·`connect_timeout=1s`) + 클라이언트 retry 결합으로 **20.77% → 11.05% (-47%)**. 단, ablation 측정으로 **nginx 튜닝 단독은 false-positive 격리로 오히려 24.51%로 악화**, 클라이언트 retry가 개선의 주역임을 확인. 운영 SLO 5% 미만은 active HC 도입 필수.

**전체 분석**: [`load-test-portfolio.md`](load-test-portfolio.md)

---

## 7. 관측성

비즈니스 핵심 지표를 Micrometer로 직접 정의 → Grafana 6패널.

| 메트릭 | 타입 | 의미 |
|--------|------|------|
| `ticketing_hold_created_total` | Counter | 홀드 생성 성공 (선점 처리량) |
| `ticketing_hold_conflict_total` | Counter | Lua false → 409 (좌석 경합 빈도) |
| `ticketing_lock_acquire_failures_total` | Counter | 락 획득 실패 → 429 |
| `ticketing_queue_waiting_count` | Gauge | 콘서트별 대기열 인원 |
| `ticketing_payment_complete_duration_seconds` | Histogram | 결제 완료 E2E 시간 |
| `ticketing_reservation_confirmed_total` | Counter | 예약 확정 수 |

Grafana 6패널: RPS · HTTP p95 · DB active/pending · 대기열 인원 · JVM threads.
헬스체크: `GET /actuator/health` → `ticketingDatastores` (Redis PING + DB `isValid`). Kafka 헬스 비활성화(부하 시 60초 타임아웃 방지).

상세 PromQL: [`monitoring.md`](monitoring.md)

---

## 8. 프로젝트 구조

```
src/main/java/com/inyoung/ticketing/
├── auth/          # JWT 인증 (Access 블랙리스트 + Refresh jti revoke)
├── concert/       # 콘서트 도메인
├── seat/          # 좌석 도메인 (AVAILABLE / HOLD / RESERVED)
├── hold/          # 좌석 선점 (Redis Lua 원자 연산)
├── queue/         # 대기열 (Redis ZSet)
├── reservation/   # 예약 확정 (DB 트랜잭션 + Outbox)
├── payment/       # 결제 (READY→APPROVED→COMPLETED, Saga 보상)
├── notification/  # Kafka→이메일/SMS/SSE
├── outbox/        # Transactional Outbox
├── lock/          # Redis 분산 락 (SETNX + Lua unlock)
├── scheduler/     # 5종 배치 (분산 락 적용)
├── metrics/       # Prometheus 커스텀 메트릭
├── common/        # AOP (멱등성·Rate Limit), 예외 처리
└── config/        # Security, Redis, Kafka, Resilience4j
```

### 주요 API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/api/queue/enter` | 대기열 진입 |
| GET | `/api/queue/status` | 순번·입장 여부 (잔여석 캐시 적용) |
| POST | `/api/holds` | 좌석 선점 (분산 락 + Lua) |
| POST | `/api/payments/{key}/complete` | 결제 완료 + 예약 확정 (Saga) |
| GET | `/api/notifications/stream` | SSE 알림 |

---

> **부하 테스트 상세**: [`load-test-portfolio.md`](load-test-portfolio.md)
> **JWT 설계**: [`jwt-auth.md`](jwt-auth.md)
> **시퀀스 다이어그램**: [`sequence-diagrams.md`](sequence-diagrams.md)
> **부록(참조)**: [`data.md`](data.md) · [`infra.md`](infra.md) · [`monitoring.md`](monitoring.md) · [`deployment-ec2.md`](deployment-ec2.md)
