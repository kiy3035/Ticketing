# 부하 테스트 리포트 (요약)

> 방법론·판정 기준·런북은 **`docs/load-test-portfolio.md`** 가 단일 소스다. 이 파일은 **결과·스크린샷만** 빠르게 붙일 때 쓴다.

## 환경 (실행 후 기입)

| 항목 | 값 |
|------|-----|
| 일시 / Run ID | |
| Git 커밋 | |
| App / Infra / k6 | |
| Hikari pool (A/B) | 10 / 30 |
| JVM 변경 (C) | |

## 핵심 결론 (3줄 이내)

1. 
2. 
3. 

## 결과 표

### `db-read.js`

| 설정 | K6_PEAK_VU | p95 | 에러% | 비고 |
|------|------------|-----|-------|------|
| 풀 10 | | | | |
| 풀 30 | | | | |
| JVM | | | | |

### `queue-flow.js`

| 설정 | K6_PEAK_VU | p95 | 에러% | 비고 |
|------|------------|-----|-------|------|
| 풀 10 | | | | |
| 풀 30 | | | | |
| JVM | | | | |

## 스크린샷

| 구분 | 파일 |
|------|------|
| db-read | `images/04-db-read-connections.png` 등 |
| queue-flow | `images/06-queue-flow-waiting-count.png` 등 |

`portfolio/images/` 기존 파일: `01-api-health-p95-bar.png`, `02-idle-baseline.png`, `03-api-health-4panel.png`, `04-db-read-connections.png`, `05-queue-ui-1242.png`, `06-queue-flow-waiting-count.png`, `07-queue-flow-3000-crash.png`, `08-jvm-threads-virtual.png`

## Knee · 병목 (한 표)

| 시나리오 | Knee (VU·RPS) | 병목 층 |
|----------|---------------|---------|
| db-read | | |
| queue-flow | | |
