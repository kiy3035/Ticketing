# 부하 테스트 실행 가이드

**용량 검증·실측·해석**은 `docs/load-test-portfolio.md` 한 파일에 둔다. 이 파일은 **백엔드 관점에서 어떤 시나리오·관측 포인트로 돌리는지** 빠르게 보기 위한 운영 노트다.

## 사전 체크
- [ ] `BASE_URL` (nginx), `CONCERT_ID` 고정
- [ ] 로그인 필요한 시나리오는 `TEST_USER` / `TEST_PASS` 준비
- [ ] Grafana 시간축이 부하 구간과 겹치도록 설정
- [ ] HikariCP / Redis pool / JVM 변경은 **한 축씩** 적용 후 재기동

## k6 스크립트 모음 (`load-tests/`)

| 스크립트 | 시나리오 | 인증 |
|---------|---------|------|
| `queue-flow.js` | 대기열 진입 → 폴링 (백엔드 핵심 경로) | 미인증 (테스트용 userId 자동 생성) |
| `knee-point.js` | 계단식 VU 증가로 knee point 탐색 (500→800→1000→1200→1500) | 미인증 |
| `concurrent-hold.js` | 같은 좌석에 100명 동시 홀드 → 1명만 성공 검증 | 인증 필요 |
| `full-flow.js` | 로그인 → 대기열 → 홀드 → 결제 전체 플로우 | 인증 필요 |
| `jwt-scenarios.js` | JWT 4가지 케이스(정상/Access만료/Refresh만료/둘 다 만료) | 동적 발급 |
| `lib/common.js`, `lib/stages.js` | 공통 헬퍼 (BASE_URL, stages 프리셋) | - |

## 실행 예시

### 대기열 부하
```bash
k6 run -e BASE_URL=http://<nginx>:80 -e CONCERT_ID=<id> load-tests/queue-flow.js
```

### Knee Point 탐색
```bash
k6 run \
  -e BASE_URL=http://<nginx>:80 \
  -e CONCERT_ID=<id> \
  -e K6_QUEUE_POLL_SLEEP_SEC=0.005 \
  load-tests/knee-point.js
```
총 소요시간 ~5분 30초. Grafana에서 RPS 곡선이 평탄해지거나 꺾이는 VU 구간을 knee point로 본다.

### 동시 좌석 홀드 (정합성 검증)
```bash
k6 run -e BASE_URL=http://<app>:8080 \
  -e TEST_USER=<u> -e TEST_PASS=<p> \
  load-tests/concurrent-hold.js
```

---

## 백엔드 관점 — 보아야 할 메트릭

### k6 출력
- `http_req_duration` p(95), p(99)
- `http_req_failed` rate
- 각 시나리오 그룹별 처리량

### Prometheus / Grafana
**커스텀 메트릭** (코드에서 직접 등록):
- `ticketing_hold_created_total{status=success}` — 홀드 성공 건수
- `ticketing_hold_conflict_total{reason=seat_already_held_redis}` — Lua EXISTS 차단
- `ticketing_lock_acquire_failures_total{operation=hold|reservation}` — 락 획득 실패
- `ticketing_reservation_confirmed_total{concert_id=...}` — 예약 확정
- `ticketing_payment_completed_total` — 결제 완료
- `ticketing_payment_complete_duration_seconds` — request→COMPLETED 소요시간 Timer
- `ticketing_outbox_published_total` — Outbox 발행 성공
- `ticketing_outbox_publish_failures_total` — Outbox 발행 실패
- `ticketing_batch_run_duration_seconds{batch=...}` — 5종 배치별 소요시간
- `ticketing_batch_run_total{batch=...,status=success|failure}` — 배치 성공/실패 카운트
- `ticketing_refund_processed_total` — 환불 처리 건수

**JVM/HTTP** (Spring Actuator + Micrometer):
- `jvm_threads_live_threads` — Platform Thread 수 (VT 적용 후 감소 확인)
- `http_server_requests_seconds_bucket` — HTTP histogram (p95/p99)
- `hikaricp_connections_active`, `hikaricp_connections_pending` — DB 커넥션 풀 상태

### Redis (Redis Insight 또는 INFO)
- `used_memory` vs `maxmemory` (400MB)
- `evicted_keys` (LRU eviction 발생량)
- `connected_clients`
- `instantaneous_ops_per_sec`

### Kafka
- Consumer lag (예: `kafka-consumer-groups --describe`)
- DLT 토픽 적재량 (`ticketing.seat-hold-events.DLT`, `ticketing.payment-complete.DLT`)
- `kafka_outbox` 테이블의 `status='FAILED'` 행 수 (운영 알람 대상)

---

## 결과 해석 워크플로우

1. **knee point 탐색**: VU를 계단식으로 올리며 RPS가 꺾이는 지점을 찾는다.
2. **꺾인 시점에 어떤 메트릭이 먼저 튀는가?**
   - `http_req_duration` p95 급등 + `hikaricp_connections_pending` 증가 → DB 커넥션 풀 병목
   - `ticketing_lock_acquire_failures_total` 증가 → Redis 락 경합 한계
   - `evicted_keys` 증가 → Redis 메모리 부족
   - Consumer lag 증가 → Kafka 처리 속도 부족
3. **튜닝 순서**:
   1. 설정 기반 (`hold.ttl`, `lock.ttl`, `queue.batch-size`, HikariCP `maximum-pool-size`, Redis `max-active`)
   2. 캐시 강화 (콘서트 목록, 잔여석)
   3. 인스턴스 수평 확장
   4. 도메인 분리 (대기열을 별도 서비스로) 또는 Redis Cluster
