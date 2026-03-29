# 부하 테스트 (k6)

실무에서 관측 축을 나눈 **5개 시나리오** + **E2E full-flow** 가 있다.  
스크립트 상단에 **Knee point / 병목** 해석 힌트가 있고, **`[조정]`** 주석이 붙은 값은 직접 바꿔가며 실험하면 된다.

## Knee point · 병목을 어떻게 볼지

1. **Knee point**: `stages`의 `target`(동시 VU)를 단계마다 올릴 때, k6 요약에서 **http_req_duration p95** 또는 **http_req_failed** 가 이전 단계 대비 “눈에 띄게” 나빠지기 시작하는 구간을 적어 둔다.
2. **병목 힌트**: 같은 시각에 Grafana/Prometheus를 본다 ([monitoring.md](../docs/monitoring.md)).
   - 대기열·status만 지연 → `ticketing_queue_waiting_count`, 큐 API
   - 홀드·락 실패율 급증 → `ticketing_lock_acquire_failures_total`
   - 결제·확정 → `ticketing_payment_completed_total` 등
   - `db-read`만 악화 → DB 풀·슬로우쿼리 의심
3. **thresholds**: 탐색 초기에는 숫자를 **완화**해 두고, knee를 찾은 뒤 SLO에 맞게 **조여**도 된다.

## 스크립트

| 축 | 파일 | 인증 |
|----|------|------|
| API 건강 | `api-health.js` | 불필요 |
| 대기열 | `queue-flow.js` | 불필요 |
| 좌석 & 홀드 | `seats-hold.js` | `TEST_USER`, `TEST_PASS` |
| DB 읽기 | `db-read.js` | 동일 |
| 캐시 핫리드 | `cache-hot-read.js` | 불필요 |
| **E2E (대기열~결제)** | **`full-flow.js`** | 동일 — **결제는 코드상 포인트(POINT) 고정**, 카드 등 미지원 |

## 환경 변수

- `BASE_URL`, `CONCERT_ID`
- `TEST_USER`, `TEST_PASS` — `seats-hold.js`, `db-read.js`, **`full-flow.js`** 필수

## 실행 예

```bash
k6 run -e BASE_URL=http://localhost:8080 load-tests/api-health.js
k6 run -e BASE_URL=http://localhost:8080 -e CONCERT_ID=1 load-tests/queue-flow.js
k6 run -e BASE_URL=http://localhost:8080 -e CONCERT_ID=1 -e TEST_USER=loaduser -e TEST_PASS=loadpass load-tests/seats-hold.js
k6 run -e BASE_URL=http://localhost:8080 -e CONCERT_ID=1 -e TEST_USER=loaduser -e TEST_PASS=loadpass load-tests/db-read.js
k6 run -e BASE_URL=http://localhost:8080 -e CONCERT_ID=1 load-tests/cache-hot-read.js

# E2E: 대기열(필요 시) → 좌석 → 홀드 → 포인트 결제 → 확정 (한 번쯤 전체 경로 검증용)
k6 run -e BASE_URL=http://localhost:8080 -e CONCERT_ID=1 -e TEST_USER=loaduser -e TEST_PASS=loadpass load-tests/full-flow.js
```

Docker 호스트에서 앱이 떠 있을 때(Windows/Mac):

```bash
k6 run -e BASE_URL=http://host.docker.internal:8080 -e CONCERT_ID=1 load-tests/queue-flow.js
```

## Prometheus

`http_req_duration`, `http_req_failed` 외에 `ticketing_*` 커스텀 메트릭은 [monitoring.md](../docs/monitoring.md) 참고.

결과 표 템플릿은 [load-test-results.md](../docs/load-test-results.md).
