# 부하 테스트 결과 (당일 메모)

> 상세 프레임은 **`docs/load-test-portfolio.md`**.

## Run 메타데이터

| 항목 | 값 |
|------|-----|
| Run ID | queue-flow-2026-04-12-pool10 |
| 일시 (TZ) | 2026-04-12, Grafana 타임스탬프 약 16:26~16:30 |
| Git 커밋 | (로컬에서 `git rev-parse --short HEAD` 후 기입) |
| 담당 | |

## 환경

| 항목 | 값 |
|------|-----|
| App | `172.31.46.152:8080`, `CONCERT_ID=43` |
| Hikari (이 런) | **max pool 10** (단계 A) |
| k6 | `queue-flow.js`, `K6_PROFILE=stress`, 피크 **800** VU, `K6_QUEUE_POLL_SLEEP_SEC=0.005` |

## `db-read.js`

| pool/JVM | K6_PEAK_VU | p95 | 에러% | 피크 RPS(대략) | 메모 |
|----------|------------|-----|-------|----------------|------|
| — | (미실행) | | | | |

## `queue-flow.js`

| pool/JVM | K6_PEAK_VU | p95 | 에러% | 피크 RPS(대략) | 메모 |
|----------|------------|-----|-------|----------------|------|
| **pool 10** | 800 | **3.06 s** | **0** | **~320/s** (Grafana), k6 합성 **~241/s** | active=10, pending>150, 대기열 ~800, JVM threads ~225; iter **74** done / **776** interrupted |

## 판정 (30초 요약)

- **Knee**: 풀 **10**에서 **active 상한 + pending 급등** 구간이 HTTP p95·RPS와 맞물림.
- **병목 층**: **DB 커넥션 풀 포화**(대기 큐잉).
- **다음 액션**: 동일 k6로 **pool 30** 재측정 → pending·p95 diff를 포폴 표에 적는다.

## 스크린샷

- Grafana 6패널: `portfolio/images/queue-flow-pool10-800vu-grafana-6panel.png`
