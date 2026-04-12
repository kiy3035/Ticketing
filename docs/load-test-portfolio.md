# 부하 테스트 · 용량 검증 (포폴 / 내부 공유용)

서비스 조직에서 흔히 쓰는 **“무엇을 증명하는지 → 어떻게 재현하는지 → 무엇을 보고 knee/병목을 말하는지”** 순서로 정리했다.  
이번 런의 **관측 패널 6개·k6 스크립트 2개·Hikari 10→30→JVM 스윕**은 그대로 두고, 그 위에 **절차·판정·비범위**를 얹었다.

---

## 1. 이번 테스트가 증명하는 것 (목적)

| 증명하고 싶은 명제 | 이번 런에서의 해석 포인트 |
|-------------------|---------------------------|
| **처리 한계(knee)가 어디인지** | VU를 올렸을 때 p95·에러·풀 pending이 **완만 → 급격**으로 바뀌는 구간 |
| **병목이 어느 층인지** | HTTP 지연만 나쁜지, **Hikari pending**이 먼저 뜨는지, **대기열 게이지**가 먼저 가는지, **스레드**가 포화인지로 1차 분류 |
| **튜닝(풀·JVM)이 효과가 있는지** | 동일 k6 파라미터에서 **A(풀10) vs B(풀30) vs C(JVM)** 만 바꿔 **차분(diff)** 비교 |

---

## 2. 범위 / 이번에 하지 않는 것 (비범위)

**포함**

- 읽기 위주 API 부하: `db-read.js`
- 대기열 진입·폴링 부하: `queue-flow.js`
- 위 조합으로 **RPS·지연·풀·대기열·스레드** 관측

**제외(이번 문서의 기본 런에서는 다루지 않음)**

- **좌석 홀드·분산락 경합**을 주된 변수로 한 스트레스 → 도메인 knee는 **부록 Phase 2**에서 짧게 권장
- 결제·외부 PG, 실제 모바일 앱 혼합 트래픽 모델
- 멀티 AZ·다중 앱 인스턴스 + ALB (스케일아웃 후 **별도 런**으로 재정의하는 것이 맞음)

비범위를 밝히는 이유는 면접·리뷰에서 **“의도적으로 단순화했다”**와 **“다음 실험은 무엇인가”**를 분리해 말하기 위함이다.

---

## 3. 고정해야 하는 것 (재현성)

한 번에 **한 축만** 바꾼다. 아래는 런마다 동일하게 두거나, 바꿨다면 **Run 메타데이터**에 반드시 적는다.

| 구분 | 고정 예시 |
|------|-----------|
| 앱·DB·Redis·Kafka 버전 / 빌드 | Git 커밋, 이미지 태그 |
| 인스턴스 타입·개수 | App N대, DB 스펙 |
| 데이터 규모 | 콘서트·좌석·대기열 시드 상태 |
| k6 | `BASE_URL`, `CONCERT_ID`, stage env(`K6_*`), `K6_PEAK_VU`, `K6_PROFILE` |
| Prometheus scrape | 간격(예: 15s) — 그래프 해석 시 **최소 2~4 스크랩**은 피크에 잡히게 유지 |

---

## 4. 워크로드 정의 (k6 = 부하 모델)

| 스크립트 | 역할(업계 용어로) | 이 프로젝트에서 건드리는 축 |
|----------|-------------------|------------------------------|
| `db-read.js` | **읽기 위주 API 믹스** | DB·커넥션 풀·캐시 미스 시 DB |
| `queue-flow.js` | **대기열 핫패스** (진입 + status 폴링) | Redis 대기열·HTTP 동시성·**VU당 RPS가 큼** |

**실행 순서 권장**: `db-read.js` → `queue-flow.js`  
(동일 `K6_PEAK_VU`라도 후자는 폴링 때문에 **서버 RPS가 훨씬 커질 수 있음** — 해석 시 반드시 구분.)

곡선·단계 길이는 `load-tests/lib/stages.js`, 변수 설명은 `load-tests/README.md`.

---

## 5. 실험 설계 (설정 스윕)

| 단계 | 변경 | 비고 |
|------|------|------|
| **A** | Hikari **max pool = 10** | 기준선 |
| **B** | Hikari **max pool = 30** | 풀 대기(pending) 완화 가설 검증 |
| **C** | **JVM 한 가지만** (예: `-Xmx`, GC 옵션, VT on/off 중 택1) | “JVM”을 한 번에 여러 개 바꾸면 **원인 귀속이 불가능** |

각 단계마다 스크립트별로 **같은 k6 파라미터**로 돌려 표를 채운다.

---

## 6. 관측 체계 (6패널 ↔ Golden Signals)

업계에서 말하는 **지연·트래픽·에러·포화**와 패널을 이렇게 맞춘다.

| Golden Signal | 이 런의 패널 / 보조 |
|---------------|---------------------|
| **Traffic** | 패널 1 전체 RPS |
| **Latency** | 패널 2 HTTP P95 (+ k6 `http_req_duration` p95) |
| **Errors** | k6 `http_req_failed` (필요 시 Prom에서 `status`·`outcome` 분해 쿼리 추가) |
| **Saturation** | 패널 3·4 Hikari active/pending, 패널 6 JVM 스레드, 패널 5 대기열 인원(업무 게이지) |

### Grafana PromQL (고정)

| # | 패널 | PromQL |
|---|------|--------|
| 1 | 전체 RPS | `sum(rate(http_server_requests_seconds_count{application="ticketing"}[30s]))` |
| 2 | HTTP P95 | `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{application="ticketing"}[1m])) by (le))` |
| 3 | DB 커넥션 active | `hikaricp_connections_active{application="ticketing"}` |
| 4 | DB 커넥션 pending | `hikaricp_connections_pending{application="ticketing"}` |
| 5 | 대기열 인원 | `ticketing_queue_waiting_count{application="ticketing"}` |
| 6 | JVM 스레드 수 | `jvm_threads_live_threads{application="ticketing"}` |

---

## 7. Knee / 병목 판정 플레이북 (짧게)

피크 구간에서 아래 순서로 본다.

1. **에러가 먼저** 오르면: 타임아웃·거절·연결 실패인지 k6 로그·HTTP 코드로 구분.
2. **p95만 급등**하고 에러는 낮으면: **pending>0 지속**, DB CPU/IO, 앱 CPU 중 무엇이 같이 움직이는지 확인.
3. **`queue-flow`에서 RPS는 더 안 오르는데 p95만 나쁨** → 처리량 포화(큐잉) 쪽 knee 후보.
4. **`db-read`에서 pending이 거의 없고 p95만 나쁨** → 쿼리·DB·네트워크·앱 CPU 등 다른 층 후보.

**동시 접속자 표현**: k6 VU는 **“동시에 시나리오를 수행하는 클라이언트 수”**다. `queue-flow`는 **사용자 1명 ≠ VU 1명**에 가깝다. 포폴에는 **“VU 기준 knee + 피크 RPS”**를 같이 쓰면 설득력이 올라간다.

---

## 8. 실행 런북 (체크리스트)

**사전**

- [ ] 앱·부속기 동작, 메트릭 스크랩 정상
- [ ] Grafana 대시보드에 위 6쿼리 반영
- [ ] `BASE_URL` / `CONCERT_ID` / 테스트 계정 고정
- [ ] 부하 전 **짧은 스모크**(VU 소수)로 시나리오 자체 오류 없음 확인

**실행**

- [ ] 스크립트 순서: `db-read.js` → `queue-flow.js`
- [ ] 단계 A→B→C 각각 **재기동·동일 k6**로 기록
- [ ] 피크는 Prometheus 스크랩 기준 **최소 1~2분**은 잡히게 (`K6_PEAK_HOLD`)

**사후**

- [ ] 계단 사이 **5~10분 휴지**(특히 t3/t3a **CPU 크레딧** 회복)
- [ ] Run ID·커밋·환경 표를 `docs/load-test-results.md`에 남김

---

## 9. 기록 표 — `db-read.js`

| 설정 단계 | K6_PEAK_VU | k6 p95 | k6 에러율 | RPS(대략) | 패널에서 본 징후 |
|-----------|------------|--------|-----------|-----------|------------------|
| A | | | | | |
| B | | | | | |
| C | | | | | |

**스크린샷**: `docs/images/load-db-read-1.png`, `load-db-read-2.png` (또는 `portfolio/images/`에 두고 링크만 통일)

---

## 10. 기록 표 — `queue-flow.js`

| 설정 단계 | K6_PEAK_VU | k6 p95 | k6 에러율 | RPS(대략) | 대기열·커넥션 메모 |
|-----------|------------|--------|-----------|-----------|-------------------|
| **A (pool 10)** | 800 | **3.06 s** | **0%** | Grafana 합성 RPS 피크 **~320/s**; k6 `http_reqs` 평균 **~241/s** | Hikari **active=10** 포화, **pending 피크 150+**, 대기열 인원 **~800**, JVM 스레드 **~40 → ~225** |
| B | | | | | (풀 30 런 후 기입) |
| C | | | | | (JVM 단계 후 기입) |

### 재현 커맨드 (이 런)

```bash
k6 run -e BASE_URL=http://172.31.46.152:8080 -e CONCERT_ID=43 \
  -e K6_PEAK_VU=800 \
  -e K6_QUEUE_POLL_SLEEP_SEC=0.005 \
  -e K6_PROFILE=stress \
  -e K6_WARM_DURATION=5s -e K6_MID_DURATION=5s -e K6_CLIMB_DURATION=10s \
  -e K6_PEAK_HOLD=35s -e K6_RAMP_DOWN=5s \
  load-tests/queue-flow.js
```

### k6 요약 (동일 런)

| 항목 | 값 |
|------|-----|
| 실행 시간 | 약 **90 s** |
| `http_req_duration` p(95) | **3.06 s** |
| `http_req_failed` | **0%** (0 / 21,720) |
| `http_reqs` | **21,720** (합성 약 **241 req/s**) |
| `checks` | **100%** 성공 (진입 201, 순번 200) |
| iteration | **74** 완료, **776** interrupted (스테이지 종료 시점에 진행 중 이터레이션 다수) |
| `vus_max` | **800** |

### Grafana 6패널 (Hikari pool 10, 피크 VU 800)

![](../portfolio/images/queue-flow-pool10-800vu-grafana-6panel.png)

**그래프에서 읽은 일치 신호** (약 16:26~16:30 구간): RPS 상승 후 **Hikari active가 10에 고정**되고, 같은 시각대 **pending이 급등(150 초과)**. HTTP P95는 **약 7 s까지 스파이크** 후 완화되는 구간이 보이며, **대기열 인원은 VU 규모(~800)와 함께 상승**. JVM live threads는 **약 225** 수준으로 플랫하게 유지.

---

## 11. 한 블록 요약 (실험 끝난 뒤 채움)

- **Knee(대략)**: `queue-flow`에서 **피크 VU 800·폴링 0.005 s** 조합일 때, **커넥션 풀(10) 포화**와 **pending 폭증**이 HTTP 지연·RPS 상한과 **동시에** 나타남 → **풀 포화가 knee 트리거**로 해석 가능.
- **병목 층**: **HikariCP 대기**(pending) + 그에 따른 **요청 지연**. k6 기준 HTTP 실패율은 0%이나, **지연·큐잉**으로 사용자 체감 품질은 크게 저하.
- **튜닝 효과**: 풀 10→30 런으로 **pending·p95**가 어떻게 변하는지 비교 표에 채운다.
- **운영 권고**: `hikaricp_connections_pending`·**HTTP p95**에 알람을 두고, 오픈 직전에는 **풀 크기·최대 연결·타임아웃**을 부하로 한 번 검증한다. **완료 iteration 74 vs interrupted 776**은 “짧은 피크 홀드 + 긴 폴링 루프”에서는 정상적으로 나올 수 있으므로, **SLO는 HTTP p95·에러율·풀 pending**으로 정의하는 편이 안전하다.

---

## 부록 Phase 2 — 도메인 knee (권장, 짧게)

예매 시스템의 **핵심 동시성**은 좌석 홀드·락이다. 이번 2스크립트만으로는 그 층의 knee를 **주장하기 어렵다**. 시간이 나면 `load-tests/seats-hold.js`를 **별도 런**으로 추가하고, 락/홀드 관련 메트릭(`docs/monitoring.md` 참고)과 함께 **한 페이지**만 붙이면 포폴 설득력이 크게 올라간다.

---

## 관련 파일

- k6 변수·시나리오: `load-tests/README.md`
- 실행 커맨드 요약: `my-docs/load-test-guide.md`
- 당일 빠른 메모: `docs/load-test-results.md`
- 대외 요약 양식: `portfolio/load-test-report.md`
