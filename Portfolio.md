# 콘서트 예매 시스템 (Concert Ticketing) — 포트폴리오 요약

> **한 줄**: 대규모 동시 접속을 가정한 콘서트 예매 백엔드. Redis·Kafka·분산 락·배치로 동시성·정합성·확장성을 설계·구현한 Spring Boot 프로젝트이다.

---

## 1. 목표

- **공정한 선착순**: 대기열로 트래픽을 나누고, 순번 기반으로 입장 허용해 동시 접속 폭증을 견딘다.
- **중복 예약 방지**: 같은 좌석을 두 명이 잡지 못하도록 분산 락과 Redis 홀드로 경쟁 구간을 제어한다.
- **실시간 알림**: 홀드 만료·예약 확정 등을 이벤트로 전파하고, SSE로 사용자에게 전달한다.
- **운영 친화**: 세션·홀드·대기열을 Redis에 두어 수평 확장을 열어 두고, 취소된 공연은 배치로 일괄 환불한다.

---

## 2. 구현 요약

| 구분 | 내용 |
|------|------|
| **도메인** | 콘서트·좌석·홀드(Redis)·대기열(Redis)·예약·결제(포인트 Mock)·알림·관리자 |
| **대기열** | Redis ZSet 기반 콘서트별 대기열, O(log N) 순번 조회, 토큰 TTL·정리 스케줄러 |
| **좌석/홀드** | 좌석 단위 분산 락, Redis TTL 홀드, Lua로 원자적 생성/해제, 만료 시 Kafka 이벤트 |
| **결제** | READY → APPROVED → COMPLETED, 완료 시 Kafka로 이메일/SMS 알림 |
| **알림** | Kafka Consumer → Redis List 저장 + SSE 푸시, 폴링 백업 |
| **배치** | 홀드 만료 스캔, 대기열 상위 N명 입장 허용, 취소된 공연 환불 |
| **세션/캐시** | Spring Session + Redis, 콘서트 목록 Redis 캐시, `queue/status` 잔여석 집계 캐시 |
| **관측** | `/api/metrics`, Actuator + Prometheus, 로그 파일 |

---

## 3. 기술 스택

- **Backend**: Java 21, Spring Boot 3.4, Spring Security, Spring Data JPA, Spring Kafka, Spring Session
- **Storage**: MySQL, Redis
- **Message**: Apache Kafka
- **알림**: Google SMTP, Solapi(SMS)
- **인프라**: Docker Compose, Gradle

---

## 4. 핵심 설계·의사결정

- **대기열 ZSet**: RANK로 순번 O(log N), CARD로 대기 인원, RANGE로 상위 N명 입장 허용에 맞춘다.
- **홀드 만료**: Redis 키 TTL만으로 만료 시 후처리가 어려우므로 `hold:expires` ZSet과 스케줄러로 스캔 후 Kafka 이벤트를 발행한다.
- **취소 공연 환불**: 건수가 많아질 수 있어 청크·재시도·실패 격리가 필요하다. 배치로 처리한다.
- **SSE**: 서버→클라이언트 푸시에 맞고, WebSocket 대비 구현·방화벽 부담이 적다. 확장 시 Sticky Session을 고려한다.
- **분산 락**: Lua로 토큰이 일치할 때만 해제해 원자성을 맞춘다.

---

## 5. 성능·확장

- **성능**: ZSet 연산, 콘서트 목록 캐시, 연결 풀, 배치 크기로 스케줄 부하를 조절한다.
- **확장**: 세션·홀드·대기열·알림이 Redis 기반이면 인스턴스를 늘릴 수 있다. Kafka Consumer Group으로 알림 소비를 나눈다.
- **배치**: `ticketing.queue.batch-size` 등은 `application.properties`에서 조정한다.

---

## 6. 프로젝트 구조

```
auth, concert, seat, hold, queue, reservation, payment, notification, metrics
config, lock, scheduler(HoldCleanup, QueueProcessing, RefundForCancelledConcert), common
```

- **문서**: 루트 `README.md`, `docs/README.md` 및 `docs/` 하위 문서. 부하·실측은 [load-test-portfolio.md](docs/load-test-portfolio.md)에 모은다.
- **실행**: `.env` 설정 후 `docker compose up -d`, `./gradlew bootRun`, `http://localhost:8080`

---

## 7. 부하 검증 메모 (`queue-flow`)

### 7.1 Hikari 풀 30 + Virtual Threads, 잔여석 캐시 없음

**조건**: 풀 10·풀 30 재현과 동일한 k6 스크립트·`K6_PEAK_VU`·폴링 간격·프로필. 회차마다 Redis 대기열·토큰만 삭제한 뒤 `k6 run`한다.

| 설정 | 진행 |
|------|------|
| Hikari pool 10 | 3회 완료 |
| Hikari pool 30 | 3회 완료 |
| Hikari 풀 30 + Virtual Threads ON, 잔여석 캐시 없음 | 3회 완료 |

**k6 요약**

| 회차 | `http_req_duration` p(95) | `http_req_failed` | 합성 RPS | `http_reqs` | checks | iteration 완료 / interrupted | 비고 |
|------|----------------------------|-------------------|----------|-------------|--------|------------------------------|------|
| 1 | 12.35 s | 0% | ~144/s | 12,999 | 100% | 50 / 790 | 약 1m30s, `vus_max` 800 |
| 2 | 2.06 s | 0% | ~376/s | 33,789 | 100% | 315 / 596 | 동일 |
| 3 | 1.95 s | 0% | ~436/s | 39,224 | 100% | 501 / 472 | 동일 |

**3회 요약**: p(95) 중앙값 **2.06 s** (범위 1.95–12.35 s), 합성 RPS 중앙 **~376/s**, `http_req_failed` 전회 0%. 1회차만 p95와 처리량이 크게 벗어났다.

**대표 Grafana 6패널** (`portfolio/images/queue-flow-pool30-vt-on-grafana-6panel.png`): k6 p(95)가 중앙값과 같고 실패율이 0%인 2회차와 동일한 캡처이다.

**Grafana에서 본 피크**

| 회차 | RPS | HTTP p95 | Hikari active | pending | 대기열 | JVM 스레드 |
|------|-----|----------|---------------|---------|--------|------------|
| 1 | ~200/s | ~15 s 구간 | 30 | ~650–700 | ~800 | ~30 |
| 2 | ~480–500/s | ~2–2.5 s | 30 | 600+ | ~900+ | ~30 |
| 3 | ~380–500/s | ~2 s 부근 | 30 | ~650 | ~1000 | ~30 |

### 7.2 잔여석 캐시 적용 이후

앱은 `GET /api/queue/status` 잔여석 집계에 Redis 캐시를 사용한다. TTL은 `ticketing.cache.queue-status-available-seats-ttl-seconds`, 홀드·예약·만료 등에서 evict한다. `ticketing.queue.batch-size`를 바꿔 가며 3회 중앙값 등으로 튜닝한다. 실측 표는 이 절에 이어 붙인다.

---

상세 구현·API·부하 방법은 `README.md`와 `docs/`를 본다.
