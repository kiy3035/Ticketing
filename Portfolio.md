# 콘서트 예매 시스템 (Concert Ticketing) — 포트폴리오 요약

> **한 줄**: 대규모 동시 접속을 가정한 콘서트 예매 백엔드. Redis·Kafka·분산 락·배치로 **동시성·정합성·확장성**을 설계·구현한 Spring Boot 프로젝트입니다.

---

## 1. 목표 (뭘 하려고 했는지)

- **공정한 선착순**: 대기열로 트래픽을 나누고, 순번 기반으로 입장 허용해 동시 접속 폭증을 견딤.
- **중복 예약 방지**: 같은 좌석을 두 명이 잡지 못하도록 분산 락 + Redis 홀드로 경쟁 구간 제어.
- **실시간 알림**: 홀드 만료·예약 확정 등을 이벤트로 전파하고, SSE로 사용자에게 즉시 전달.
- **운영 친화**: 세션/홀드/대기열을 Redis로 두어 수평 확장 가능하게 하고, 취소된 공연은 배치로 일괄 환불.

---

## 2. 구현 요약 (뭘 했는지)

| 구분 | 내용 |
|------|------|
| **도메인** | 콘서트·좌석·홀드(Redis)·대기열(Redis)·예약·결제(포인트 Mock)·알림·관리자 |
| **대기열** | Redis ZSet 기반 콘서트별 대기열, O(log N) 순번 조회, 토큰 TTL·정리 스케줄러 |
| **좌석/홀드** | 좌석 단위 분산 락, Redis TTL 홀드, Lua로 원자적 생성/해제, 만료 시 Kafka 이벤트 |
| **결제** | READY → APPROVED → COMPLETED (포인트 차감), 완료 시 Kafka로 이메일/SMS 알림(notiType 기반) |
| **알림** | Kafka Consumer → Redis List 저장 + SseNotificationService로 SSE 푸시, 폴링 백업 |
| **배치** | 홀드 만료 스캔(60초), 대기열 상위 N명 입장 허용(2초), **취소된 공연 환불(5분, 청크 50)** |
| **세션/캐시** | Spring Session + Redis, 콘서트 목록 Redis 캐시(5분 TTL) |
| **관측** | `/api/metrics`(접속자·콘서트·예약), Actuator + Prometheus, 로그 파일 |

---

## 3. 기술 스택

- **Backend**: Java 21, Spring Boot 3.4, Spring Security, Spring Data JPA, Spring Kafka, Spring Session
- **Storage**: MySQL(영구), Redis(세션·홀드·락·캐시·대기열·알림)
- **Message**: Apache Kafka (홀드/예약/결제 완료 이벤트)
- **알림**: Google SMTP(이메일), Solapi(SMS)
- **인프라**: Docker Compose(Kafka, Redis, Kafka UI, Redis Insight), Gradle

---

## 4. 핵심 설계·의사결정 (면접 대비)

- **대기열을 ZSet으로 한 이유**: RANK로 순번 O(log N), CARD로 대기인원 O(1), 상위 N명 RANGE로 배치 입장 허용에 적합.
- **홀드 만료를 스케줄러+ZSet으로 한 이유**: Redis 키 TTL만으로는 “만료 시점에 무언가 하기”가 어려우므로, `hold:expires` ZSet에 만료 시각을 스코어로 넣고 스케줄러가 주기적으로 스캔해 Kafka 이벤트 발행.
- **취소 공연 환불을 배치로 한 이유**: 한 공연 취소 시 수백~수천 건 환불이 생길 수 있어, 청크 단위·재시도·실패 격리가 필요. 이벤트 한 건씩 처리보다 배치가 적합.
- **SSE 선택 이유**: 서버→클라이언트 푸시만 필요하고, WebSocket보다 구현·방화벽 이슈가 적음. 인스턴스당 연결 관리이므로 확장 시 Sticky Session 고려.
- **분산 락**: Lua로 “같은 토큰일 때만 삭제”해 락 해제의 원자성·안전성 확보.

---

## 5. 성능·확장 포인트

- **성능**: ZSet RANK/RANGE, 캐시로 콘서트 목록 DB 부하 감소, Redis·DB 연결 풀, 배치 크기로 스케줄 부하 조절.
- **확장**: 세션·홀드·대기열·알림이 모두 Redis라 다중 인스턴스 가능; Kafka Consumer Group으로 알림 소비 분산.
- **배치**: 대기열 2초/50명, 홀드 만료 60초/200건, 환불 5분/50건으로 주기·크기 설정 가능.

---

## 6. 프로젝트 구조 (핵심만)

```
auth, concert, seat, hold, queue, reservation, payment, notification, metrics
config, lock, scheduler(HoldCleanup, QueueProcessing, RefundForCancelledConcert), common
```

- **문서**: `README.md`, `docs/README.md`, `docs/architecture.md`, `docs/decisions.md`, `docs/sequence-diagrams.md`, `docs/api.md`, `docs/data.md`, `docs/infra.md`, `docs/deployment-ec2.md`, `docs/load-test-portfolio.md` (부하·실측·해석 단일 문서), `docs/monitoring.md`, `docs/admin-setup.md`
- **실행**: `.env` 설정 후 `docker compose up -d` → `./gradlew bootRun` → `http://localhost:8080`

---

이 문서는 포트폴리오·면접 시 “무엇을 목표로 했고, 무엇을 어떻게 구현했는지”를 한눈에 보여주기 위한 요약입니다. 상세는 `README.md`와 `docs/`를 참고하면 됩니다.
