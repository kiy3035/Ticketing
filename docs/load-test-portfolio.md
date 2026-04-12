# 부하 테스트 · 용량 검증 (포폴용, 단일 문서)

부하 설계·실행·**실측 결과·해석**까지 이 파일 하나에 둔다. (구 `load-test-results.md`, `portfolio/load-test-report.md` 역할 통합)

---

## 0. 한눈에 (면접용)

| 질문 | 한 줄 답 |
|------|----------|
| 무슨 부하? | `queue-flow.js` — 대기열 진입 + `status` **고빈도 폴링** (동일 k6, Hikari만 10↔30) |
| 무슨 지표? | Grafana **6패널**(RPS, HTTP p95, Hikari active/pending, 대기열 인원, JVM 스레드) + k6 요약 |
| 풀 10에서 본 것? | **active=10 고정 + pending 150+** → **커넥션 풀 포화**가 지연과 맞물림 |
| 풀 30에서 본 것? | **pending ~25로 급감** → “풀에서 기다리는 병목”은 완화. 그러나 **HTTP p95는 악화** → 병목이 **DB/처리량/큐** 쪽으로 이동했을 가능성 |
| “성능 개선?” | **연결 대기(pending) 관점에서는 개선**. **종단 p95만 놓고 보면 이번 A/B에서는 개선 아님** — 풀만 키우면 끝이 아님을 보여 주는 사례 |
| **Virtual Thread (캐시 없이)?** | **Hikari 30 + VT ON**만 적용 시: JVM **~32**인데 **pending ~700**·**p95 ~16 s**·**에러 2.62%** → **스레드가 아니라 DB 풀 큐**가 벽. **§9** 참고. |
| **VT + 잔여석 Redis 캐시?** | 최신 런: k6 **p(95)=453 ms**, **에러 0%**, **~853 req/s**, Grafana **pending 0**, **active 피크 ~13**, RPS **~1000/s** 근방, **JVM ~31**. **§4·§10**·`queue-flow-pool30-vt-cache-run-grafana-6panel.png`. |
| **stress+ PEAK 1500?** | 동일 앱(풀30·**batch 50**·VT·캐시)에서 **VU 1500**: k6 **p(95)=781 ms**, **에러 0%**, **~808/s**, **pending·active(30) 재등장**, 대기열 **~2250** 누적, **max ~60 s** tail. **§4.1**·`queue-flow-stress-plus-1500vu-grafana-6panel.png`. |
| **stress+ + batch 100?** | 풀30 유지·**`queue.batch-size=100`** 만 변경: k6 **p(95)=2.79 s**, **에러 0.14%**, **enter 타임아웃**, **pending 피크 ~250**, RPS **~590/s** — **§4.1 대비 악화**. **§4.4**·`queue-flow-pool30-batch100-1500vu-grafana-6panel.png`. |
| **풀 50 + stress+ 1500?** | **의도**는 Hikari **50**이나 Grafana **active는 30 한계** — 배포·설정 검증 필요. k6 **p(95)=9.86 s**, **에러 0.05%**, **pending 피크 ~500**, 대기열 **1000+**. **§4.3**·`queue-flow-pool50-1500vu-grafana-6panel.png`. |

---

## 1. 목적 · 비범위

**목적**: 동일 워크로드에서 **Hikari max pool 10 vs 30**만 바꿔, **어느 층이 포화되는지**와 **지표가 어떻게 엮이는지**를 Prometheus/Grafana + k6로 남긴다.

**비범위**: 좌석 홀드·락 경합 중심 스트레스(`seats-hold.js`), 멀티 앱+ALB 스케일아웃 검증은 별도 문서/런으로 둔다.

---

## 2. 재현 조건

| 항목 | 값 |
|------|-----|
| 스크립트 | `load-tests/queue-flow.js` |
| `K6_PEAK_VU` | **1200** (`stress+`: 피크 VU ↑. §4 과거 표와 맞출 때는 **800**) |
| `K6_QUEUE_POLL_SLEEP_SEC` | **0.002** (`stress+`: 폴링 간격 ↓ → status RPS ↑. §4 과거 표와 맞출 때는 **0.005**) |
| `K6_PROFILE` | stress |
| Stage | `5s + 5s + 10s + 35s + 5s` (합 ~60s 램프·피크 구간) |
| 앱 변경 | **Hikari max pool만** 10 → 30 (그 외 동일 가정) |

*참고*: **§4** pool 10/30·VT·캐시 표 숫자는 **PEAK 800 · poll 0.005** 런이다. 새로 돌린 **stress+** 결과와 섞어 쓰지 말고, 표에 적을 때는 env를 한 줄로 명시한다. **§4.1** 1500 VU 런은 아래 명령과 동일하되 **`K6_PEAK_VU=1500`** 만 교체한 실측이다.

```bash
k6 run -e BASE_URL=<앱>:8080 -e CONCERT_ID=<id> \
  -e K6_PEAK_VU=1200 \
  -e K6_QUEUE_POLL_SLEEP_SEC=0.002 \
  -e K6_PROFILE=stress \
  -e K6_WARM_DURATION=5s -e K6_MID_DURATION=5s -e K6_CLIMB_DURATION=10s \
  -e K6_PEAK_HOLD=35s -e K6_RAMP_DOWN=5s \
  load-tests/queue-flow.js
```

곡선·변수: `load-tests/lib/stages.js`, `load-tests/README.md`.

---

## 3. Grafana 6패널 (PromQL)

| # | 패널 | PromQL |
|---|------|--------|
| 1 | 전체 RPS | `sum(rate(http_server_requests_seconds_count{application="ticketing"}[30s]))` |
| 2 | HTTP P95 | `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{application="ticketing"}[1m])) by (le))` |
| 3 | DB active | `hikaricp_connections_active{application="ticketing"}` |
| 4 | DB pending | `hikaricp_connections_pending{application="ticketing"}` |
| 5 | 대기열 인원 | `ticketing_queue_waiting_count{application="ticketing"}` |
| 6 | JVM 스레드 | `jvm_threads_live_threads{application="ticketing"}` |

**Golden Signals 대응**: Traffic→①, Latency→②+k6, Errors→k6 `http_req_failed`, Saturation→③④⑤⑥.

---

## 4. 기록 표 — `queue-flow.js` (실측)

**Grafana 캡처 PNG**는 `docs/` 기준 **`../portfolio/images/`** 아래 **다음 파일**을 쓴다(경로·이름 고정).

| 파일 | 쓰는 곳 |
|------|---------|
| `queue-flow-pool10-800vu-grafana-6panel.png` | §5 Run A |
| `queue-flow-pool30-800vu-grafana-6panel.png` | §6 Run B |
| `queue-flow-pool30-vt-on-grafana-6panel.png` | §9 Virtual Thread 런 (**캐시 없음**) |
| `queue-flow-pool30-vt-cache-run-grafana-6panel.png` | §10 **잔여석 캐시 적용** 런 (pool 30 + VT + Redis `availableSeatCount`) |
| `queue-flow-stress-plus-1500vu-grafana-6panel.png` | §4.1 **stress+ PEAK 1500 VU** (풀 30·**batch 50**·VT·캐시, k6만 강화) |
| `queue-flow-pool30-batch100-1500vu-grafana-6panel.png` | §4.4 **stress+ 1500 + `queue.batch-size=100`** (풀 30·§4.1과 동일 k6) |
| `queue-flow-pool50-1500vu-grafana-6panel.png` | §4.3 **Hikari 풀 50(의도) + stress+ 1500 VU** (§4.1과 동일 k6. **메트릭상 active 30** → 설정 반영 여부 확인) |

| 설정 | VU | k6 p95 | 에러 | RPS(참고) | 커넥션·큐 요약 |
|------|-----|--------|------|-----------|----------------|
| **pool 10** | 800 | **3.06 s** | **0%** | Grafana 피크 **~320/s**, k6 합성 **~241/s** | active **10** 포화, **pending 150+**, 대기열 **~800**, JVM **~225** |
| **pool 30** | 800 | **5.37 s** | **0%** | Grafana 피크 **~300/s**, k6 **~187/s** | active **30** 포화 구간, **pending 피크 ~25**, Grafana p95 **~10.5 s**, 대기열 **~850** 피크·이후 **700~800** 잔존, JVM **~230** |
| **pool 30 + VT ON** | 800 | **16.02 s** | **2.62%** | Grafana 피크 **~200/s**, k6 **~145/s** | active **30** 고정, **pending ~700** 근방, Grafana HTTP p95 **~19 s** 구간, 대기열 **~800**, JVM **~30–32** |
| **pool 30 + VT ON + status 잔여석 캐시** | 800 | **453 ms** (k6 p95) | **0%** | Grafana 피크 **~1000/s**, k6 **~853/s** | **pending 0**, active **피크 ~13**, Grafana HTTP p95 **~0.55 s** 피크, 대기열 **~1500**, JVM **~31** |
| **stress+ PEAK 1500** (풀30·**batch 50**·캐시) | **1500** | **781 ms** (k6 p95) | **0%** | Grafana **~800–900/s**, k6 **~808/s** | active **30** 포화, **pending 피크 ~35**, Grafana HTTP p95 **종료 직전 스파이크(~30 s 구간)**, 대기열 **~2250**까지 상승, JVM **~30–31** |
| **stress+ PEAK 1500** (풀30·**batch 100**·캐시) | **1500** | **2.79 s** (k6 p95) | **0.14%** | Grafana **~800/s**, k6 **~590/s** | active **30** 포화, **pending 피크 ~250**·**~180**, Grafana HTTP p95 **~5 s** 근방, 대기열 **~1750**, **`/api/queue/enter` 타임아웃** 다수, JVM **~31** |
| **stress+ PEAK 1500 + Hikari 풀 50(의도)** | **1500** | **9.86 s** (k6 p95) | **0.05%** | Grafana 피크 **~550/s**, k6 **~430/s** | Grafana **active 피크 30**(설정 50과 불일치 시 배포 확인), **pending 피크 ~500**, HTTP p95 **~10–18 s** 구간, 대기열 **1000+**, JVM **~30** |

*참고*: 위 **pool 30** 행은 VT 전환 **이전**에 돌린 동일 k6 런(문서상 “VT 미명시”)이다. **pool 30 + VT ON**과 직접 비교할 때는 **커밋·쿨다운·시드**를 맞춘 뒤 한 번 더 돌리면 설득력이 올라간다. **마지막 행**은 `GET /api/queue/status` 잔여석 집계 **Redis 캐시(TTL 2s) + evict** 적용 후 **최신** 실측이며, Grafana는 **`queue-flow-pool30-vt-cache-run-grafana-6panel.png`** 와 같은 런이다. (이전에 문서에만 적어 두었던 **p95 2.37 s** 런은 중간 측정으로, 동일 조건 재실행 시 **더 유리하게** 나온 것으로 갱신했다.)

### k6 상세 (동일 스크립트)

| 항목 | pool 10 | pool 30 | pool 30 + VT ON | pool 30 + VT + 잔여석 캐시 |
|------|---------|---------|-----------------|---------------------------|
| `http_reqs` | 21,720 | 16,839 | 13,045 | **75,303** |
| `checks` | 100% | 100% | **97.37%** (순번 조회 실패 343) | **100%** |
| iteration 완료 | 74 (interrupted 776) | 50 | 67 | **1440** (interrupted **158**) |
| `vus_max` | 800 | 800 | 800 | 800 |
| `http_req_duration` p(95) | 3.06 s | 5.37 s | 16.02 s | **452.61 ms** |

**stress+ PEAK 1500 VU** (§4.1, 앱은 캐시·풀30·VT 유지 / k6만 `K6_PEAK_VU=1500`, §2 stress+ 폴링·스테이지):

| 항목 | 값 |
|------|-----|
| `http_reqs` | **72,657** (합성 **~808/s**) |
| `checks` | **100%** |
| iteration | **1377** 완료, **889** interrupted |
| `vus_max` | **1500** |
| `http_req_duration` p(95) / max | **781.14 ms** / **~59.6 s** |

**stress+ PEAK 1500 VU, `ticketing.queue.batch-size=100`** (§4.4, §4.1과 동일 k6·풀30·캐시·VT / **배치만 50→100**):

| 항목 | 값 |
|------|-----|
| `http_reqs` | **53,125** (합성 **~590/s**) |
| `checks` | **99.85%** (`대기열 진입 201` 실패 **78**) |
| iteration | **954** 완료 (k6 출력 기준) |
| `vus_max` | **1500** |
| `http_req_duration` p(95) / max | **2.79 s** / **~60 s** |
| 기타 | k6 **`request timeout`** on `POST /api/queue/enter` (로그 다수) |

**stress+ PEAK 1500 VU, Hikari max-pool 50(의도)** (§4.3, §4.1과 동일 k6·캐시·VT / 앱만 `spring.datasource.hikari.maximum-pool-size=50`으로 올린 실험):

| 항목 | 값 |
|------|-----|
| `http_reqs` | **38,665** (합성 **~430/s**) |
| `checks` | **99.94%** (`대기열 진입 201` 실패 **23**/38665) |
| iteration | **256** 완료 (k6 출력 기준) |
| `vus_max` | **1500** |
| `http_req_duration` p(95) / max | **9.86 s** / **~60 s** |

---

### 4.1 Run stress+ — **PEAK 1500 VU** (`queue-flow`, 캐시·풀30·**batch 50**·VT 동일)

![](../portfolio/images/queue-flow-stress-plus-1500vu-grafana-6panel.png)

**그래프가 말하는 순서**  
RPS는 **~800–900/s** 부근에서 플래토를 이룬다. **800 VU 캐시 런**에서 보던 **~1000/s**보다 낮거나 비슷한데, **VU는 1500**이므로 “유저 수만 올리면 RPS가 비례해 오른다”는 가설은 **지지되지 않는다** — 이미 **처리 한도**에 가깝다고 읽는 것이 타당하다.

**Hikari active가 30에 붙고 pending이 수십까지 튄다**는 것은, **캐시 런에서 사라졌던 DB 풀 큐잉**이 **부하 강화 후 다시 나타난** 증거다. 즉 **status 캐시로 줄었던 DB 압력**만으로는 **동시 접속을 무한히 흡수**할 수 없고, **다른 경로·스케줄·풀 상한**이 다시 병목으로 드러난 그림이다.

**대기열 인원이 ~2250까지 거의 선형으로 올라간다**는 것은, **입장 허용(스케줄) + HTTP 처리**가 **유입 속도**를 따라가지 못해 **백로그가 쌓이는 구간**이 있다는 뜻이다. “에러 0%”와 동시에 보면 **서비스는 살아 있지만 공정성·체감 대기**는 나빠질 수 있다.

**HTTP p95는 대부분 낮게 유지되다가 종료 직전에 크게 튄다**는 패턴은 k6의 **max ~60 s**와 맞물려, **tail latency**와 **스테이지 종료·램프다운**이 겹친 구간을 의심할 수 있다. 면접에서는 “**p95만 보면 781 ms로 멀쩡해 보이지만, max·pending·큐 길이를 같이 본다**”고 말하면 된다.

**JVM live threads ~30–31**은 **VT 기대치**와 맞고, **스레드 폭주** 그림은 아니다. 병목은 **OS 스레드 수**가 아니라 **DB 풀·처리량** 쪽으로 다시 기운다.

**k6 interrupted 889**는 **피크 홀드가 짧고** 이터레이션이 **폴링 루프로 길어질** 수 있어, **스테이지 끝에서 끊긴 시도**가 많다는 뜻으로 해석한다(§5와 같은 주의).

---

### 4.2 튜닝 메모 — **`ticketing.queue.batch-size`(50)** 과 **Hikari 풀 확대**

**batch-size가 하는 일**  
`QueueProcessingScheduler`는 `processing-interval-ms`(기본 2초)마다 공연별로 **상위 N명**(`getTopTokens(..., batchSize)`)을 꺼내, `min(batchSize, 예매가능좌석)` 범위에서 `allowEntry`를 호출한다. 입장 허용 자체는 **Redis** 위주이고, 틱마다 공연당 **좌석 COUNT 쿼리 2회**는 **공연 수**에 비례한다.

**50을 키우면 기대되는 것**  
이론상 **같은 2초 안에 더 많은 사람**에게 입장 허용을 줄 수 있어, **“대기열 ZSET에서 빠져 나가는 속도(허용 레이트)” 상한**은 올라간다. 다만 **실제 체감**은 `status` 폴링·DB·풀·다른 API와 **같은 자원**을 쓰므로, batch만 크게 하면 **스케줄 한 틱이 길어지거나** Redis/락 경합이 늘 수 있다.

**실측(§4.4)**: 동일 **stress+ 1500·풀 30**에서 **batch 50→100**은 **p95·pending·RPS·타임아웃이 악화**됐다. **무조건 키우면 좋아진다**는 가설은 이 데이터로 기각된다.

**50의 “근거”**  
코드상 **도메인 유도값은 아니고** `TicketingProperties` 기본값·`activation-threshold`(50)와 **숫자만 맞춘 설계 여지**가 있을 뿐이다. **근거는 부하 테스트로 잡는 것**이 맞다.

**Hikari `maximum-pool-size`를 50으로**  
`pending`이 다시 보이는 런에서는 **풀 확대**가 **연결 대기 완화**에 도움이 될 **가능성**이 있다. 반면 **RDS `max_connections`**, **앱 인스턴스 수 × 풀**, **MySQL CPU/락**을 넘기면 **다음 병목**으로 이동한다(문서 §6·§9 교훈). **한 축만** 올리고 Grafana·슬로우 쿼리를 같이 보라.

---

### 4.3 Run stress+ — **PEAK 1500 VU**, **Hikari 풀 50(의도)** (`queue-flow`)

![](../portfolio/images/queue-flow-pool50-1500vu-grafana-6panel.png)

**한 줄 요약**  
동일 **stress+ 1500 VU**인데 **§4.1(풀 30)** 대비 **처리량·지연·에러가 크게 악화**됐다. “풀만 키우면 좋아진다”는 가설은 **이 데이터로는 지지되지 않는다**.

**그래프·k6가 말하는 것**  
- **RPS ~550/s**, k6 **~430/s** — §4.1의 **~800/s**보다 **낮다**. 동시 부하인데 **오히려 덜 처리**됐다고 읽을 수 있다.  
- **HTTP p95** Grafana **~10–18 s**, k6 **p(95)=9.86 s** — §4.1 **~0.78 s** 대비 **한 자릿수 초**로 악화.  
- **Hikari pending 피크 ~500** — §4.1의 **~35**보다 **한두 단계 큰 큐잉 재난** 수준이다.  
- **대기열 인원 1000+** 상승 — 유입 대비 소진이 더 느려진 패턴.  
- **k6 `대기열 진입 201` 23건 실패**, **http_req_failed 0.05%** — **무결성은 대부분 유지**되나 SLO 관점에서는 **균열**이 보인다.

**가장 중요한 검증 포인트 — active가 여전히 30**  
Grafana **DB active**가 **30에서 더 올라가지 않는다**면, (1) **실행 중인 앱에 `maximum-pool-size=50`이 반영되지 않았거나**(이전 JAR·다른 프로필·환경변수 덮어쓰기), (2) **다른 DataSource/Hikari 빈**이 30으로 묶여 있거나, (3) **RDS/보안그룹·프록시 단계의 세션 한도** 등 **풀 밖 상한**이 있을 수 있다. **Actuator `hikaricp.connections.max`** 또는 설정 로그로 **실효 풀 상한**을 먼저 확인해야 이 런을 “풀 50 실험”으로 해석할 수 있다. **지금 메트릭만 보면 “풀 50 효과”는 측정되지 않았다**고 쓰는 것이 정직하다.

**§4.1과의 대비(포폴 문장)**  
같은 k6 곡선인데 **지연·pending·처리량이 모두 나빠진** 경우, 원인 후보는 **풀 숫자 하나**에만 있지 않다. **배포 전제·DB 쪽 경합·캐시 적중률·런 타이밍**을 같이 의심하고, **한 축 변경 후 반드시 “설정이 실제로 적용됐는지”**를 메트릭으로 증명하는 절차를 밟는다는 교훈으로 쓸 수 있다.

---

### 4.4 Run stress+ — **`queue.batch-size=100`** (풀 30·§4.1과 동일 k6)

![](../portfolio/images/queue-flow-pool30-batch100-1500vu-grafana-6panel.png)

**한 줄 요약**  
**§4.1(batch 50)** 과 **동일 부하·동일 풀 30**인데 **`ticketing.queue.batch-size`만 100**으로 올리면, **지연·pending·처리량·에러가 모두 나빠진다**. “배치를 키우면 대기열이 빨리 비워진다”는 가설은 **이 데이터로는 지지되지 않는다**.

**§4.1 대비 숫자(같은 PEAK 1500)**  
| 지표 | §4.1 (batch **50**) | §4.4 (batch **100**) |
|------|---------------------|------------------------|
| k6 p(95) | **~781 ms** | **~2.79 s** |
| `http_req_failed` | **0%** | **0.14%** |
| 합성 RPS | **~808/s** | **~590/s** |
| Hikari `pending` 피크 | **~35** | **~250** / **~180** |
| 대기열 인원(그래프) | **~2250** | **~1750** (스테이지 길이·시작 시각에 따라 단순 비교는 제한) |

**그래프가 말하는 것**  
- **active=30**은 그대로인데 **pending이 한 자릿수에서 세 자릿수로** 튄다 → **풀 슬롯은 그대로**인데 **연결을 기다리는 쪽이 폭증**했다.  
- **HTTP p95가 ~5 s 근방**까지 올라간 구간은 **전 구간 tail**로 읽히고, k6 **max ~60 s**와 맞물린다.  
- **RPS가 §4.1보다 낮다** → 배치를 키웠다고 **처리량이 늘지 않았다**.

**k6 `request timeout` on `POST /api/queue/enter`**  
스케줄러 배치는 **입장 허용 루프**이고 `enter`는 **별 HTTP**이지만, **동일 DB 풀·동일 프로세스**를 공유한다. **한 틱에 `batch`만큼 토큰을 더 처리**하면 `processQueue()` 실행 시간이 길어지고(분산 락 구간 포함), 그 사이 **다른 요청이 커넥션을 오래 잡거나** 대기가 길어져 **`enter`가 타임아웃**까지 갈 수 있다는 **해석 프레임**을 쓸 수 있다(정식 프로파일링 전 가설).

**결론(포폴)**  
**batch 50 → 100**은 이 환경에서는 **개선이 아니라 악화**였다. **“입장 처리량 상한”만 보지 말고**, **풀 pending·HTTP tail·타임아웃**을 같이 두고 배치를 튜닝해야 한다.

---

## 5. Run A — Hikari **pool 10** (해석)

![](../portfolio/images/queue-flow-pool10-800vu-grafana-6panel.png)

**그래프가 말하는 순서**  
부하가 올라가면서 RPS가 증가한다. 그 직후 **Hikari active가 10에 붙어 더 이상 올라가지 않는다** — 이 시점부터 “DB 커넥션 풀의 **물리 슬롯**”은 이미 다 쓴 상태다. 동시에 **pending이 세 자릿수(150+)로 튄다**는 것은, 워커 스레드가 SQL을 실행하려 해도 **풀에서 커넥션을 못 받아 큐에서 대기하는 요청**이 쌓였다는 뜻이다. 즉 **처리량(RPS)** 과 **연결 획득 대기**가 같은 시간축에서 악화되며, 전형적인 **풀 포화 → 큐잉 → 지연** 패턴이다.

**HTTP P95가 ~7 s까지 튀는 구간**은 서버 히스토그램 기준 tail latency가 한꺼번에 나빠진 것으로 읽힌다. k6 클라이언트 p95(3.06 s)와 숫자가 다르게 나오는 것은 정상에 가깝다: **집계 윈도**(1m vs 전체 런), **클라이언트가 본 경로**(DNS·TCP·keep-alive), **요청 믹스**가 다르기 때문이다. 면접에서는 “**서버와 클라이언트 지표를 같이 두고, 방향이 같이 나쁜지** 본다”고 말하면 된다.

**대기열 인원 ~800**은 k6 VU 스케일과 맞물려, “큐에 쌓인 업무 부하”가 모니터링 게이지로도 드러난다. **JVM 스레드 ~225**까지 오른 것은 동시에 처리하려는 요청이 많다는 **증거**이지, 그 자체가 “스레드 풀이 한계”라는 뜻은 아니다(가상 스레드 등 설정에 따라 다름).

**k6 에러 0%**는 “HTTP 레벨에서 대량 실패(5xx 폭탄)”는 없었다는 뜻이지, **SLO를 만족했다**는 뜻은 아니다. 사용자 체감은 p95·대기 시간으로 이미 나빠져 있다.

**iteration 74 vs interrupted 776**은 피크 홀드가 짧고, 한 이터레이션이 폴링 루프로 길어질 수 있어 **스테이지 종료 시점에 진행 중이던 이터레이션이 끊긴 것**으로 해석하는 것이 타당하다. 그래서 **SLO는 “완료 iteration 수”보다 http p95·pending·에러**로 잡는 편이 안전하다.

---

## 6. Run B — Hikari **pool 30** (해석)

![](../portfolio/images/queue-flow-pool30-800vu-grafana-6panel.png)

**가장 먼저 눈에 들어와야 할 것 — pending**  
풀 10에서 문제였던 **pending 폭증이 ~25 수준의 짧은 스파이크**로 줄었다. 이건 “**애플리케이션 커넥션 풀 앞에서 기다리던 병목**”은 확실히 줄었다는 **강한 증거**다. 면접에서 **“튜닝이 먹혔는지”**를 물으면, **pending 차트만으로도 1차 답은 Yes**라고 말할 수 있다.

**그런데 HTTP P95는 오히려 더 크게 튄다 (~10.5 s)**  
이건 **“풀을 키웠더니 모든 게 좋아졌다”**로 쓰면 거짓이 된다. 해석은 이렇게 가져가는 것이 성숙하다: 풀을 키우면 **동시에 DB에 붙을 수 있는 세션 수**가 늘어난다. 그 결과 **풀 큐에서의 대기**는 줄지만, **MySQL 쪽에서의 경합**(CPU, 락, `Threads_running`)이나 **앱의 다른 임계**(스케줄링, Redis, 대기열 처리량)가 **다음 병목**으로 부각될 수 있다. 즉 **병목이 “풀”에서 “DB/비즈니스 처리” 쪽으로 이동**한 그림으로 읽는 것이 타당하다.

**대기열 인원이 ~850까지 올라가고, RPS가 떨어진 뒤에도 700~800대가 잠시 남는다**는 것은, **HTTP 요청 속도**와 **“큐에 쌓인 사람 수”**가 같은 속도로 비워지지 않는다는 뜻이다. 부하가 꺼진 뒤에도 게이지가 따라오는 **백로그 소진 구간**이 보이면, “단순 RPS만 보고 성공 판단하면 안 된다”는 주장에 근거가 된다.

**k6 요청 수(16,839)가 풀 10(21,720)보다 적다**는 것은, 같은 VU라도 **서버가 느려지면 이터레이션당 처리량이 줄어든다**는 뜻으로 읽을 수 있다(또는 런 간 미세한 환경 차). **p95 5.37 s**는 풀 10보다 **악화**다.

---

## 7. 10 vs 30 — 한 덩어리로 정리

| 관점 | pool 10 | pool 30 | 포폴에서 쓸 문장 |
|------|---------|---------|------------------|
| **연결 풀 대기** | pending **150+** | pending **~25** | “풀 포화로 인한 **대기 큐**는 정량적으로 제거에 가깝게 줄였다.” |
| **체감 지연 (p95)** | k6 **3.06 s**, Grafana **~7 s** 근방 | k6 **5.37 s**, Grafana **~10.5 s** | “**종단 지연은 개선되지 않았고**, 오히려 악화된 구간이 있었다.” |
| **해석** | 풀이 1차 병목 | 풀 다음 단계(DB 동시 실행·큐 백로그) 의심 | “**한 축만 튜닝하면 병목이 이동**한다”는 교훈 |

**결론 문장(복붙용)**  
이번 A/B는 **“Hikari pending으로 보이는 연결 대기 병목”을 풀 크기로 완화할 수 있음**을 보여 준다. 동시에 **“풀만 크게 하면 p95가 자동으로 내려간다”는 가설은 이 데이터로는 지지되지 않는다** — **RDS CPU, 슬로우 쿼리, `max_connections`, 대기열 처리 주기**를 다음 관측 축으로 두는 것이 맞다.

---

## 8. 런북 체크 (요약)

- 한 번에 **한 축**(풀 / JVM 등)만 변경한다.
- 부하 전후 **쿨다운**을 두고, 가능하면 **Git 커밋·인스턴스 타입**을 표에 적는다.
- 새 런 결과는 **§4 표 + §5~7 스타일 해석**을 이 파일에 이어 붙인다.
- **Virtual Thread** 전후 비교는 **§9**에만 추가한다(풀 10/30 표와 **섞지 말고** 비교 전제를 한 줄로 명시).
- **`queue/status` 잔여석 캐시** 전후 수치는 **§10** 표에만 적어, §9(VT만)와 **커밋 전제**를 섞지 않는다.

---

## 9. Virtual Thread — Hikari **pool 30** + **VT ON** (`queue-flow`)

**전제**: 동일 **`queue-flow.js`**·동일 **VU 800**·동일 stage env. 앱은 **Hikari max pool = 30** + **Virtual Thread 활성화** 상태. (같은 pool 30이나 **VT 이전**에 측정한 런은 §4 표의 **pool 30** 행.)

### 9.1 재현 조건

| 항목 | 값 |
|------|-----|
| VT | **ON** |
| Hikari max pool | **30** |
| 스크립트·k6 | 본 런: `queue-flow`, **PEAK 800**, **poll 0.005**, stress (§2 stress+와 **다름**) |
| Grafana 구간(대략) | **16:54~16:56** |

### 9.2 k6 요약 (본 런)

| 항목 | 값 |
|------|-----|
| `http_req_duration` p(95) | **16.02 s** |
| `http_req_failed` | **2.62%** (343 / 13,045) |
| `checks_succeeded` | **97.37%** — `순번 조회 200`에서 실패 다수 |
| `http_reqs` | **13,045** (합성 **~145/s**) |
| iteration 완료 | **67** |
| `vus_max` | **800** |

### 9.3 Grafana 6패널

![](../portfolio/images/queue-flow-pool30-vt-on-grafana-6panel.png)

**그래프가 말하는 것 (한 줄씩)**  
- **RPS**: 피크 **~200/s** 부근 — 같은 VU인데 **pool 30(VT 미명시) ~300/s**보다 낮다. 처리량이 **지연·실패·큐잉**으로 깎인 상태로 읽을 수 있다.  
- **HTTP p95**: **~19 s**까지 붙는 구간 — tail latency가 매우 나쁘다.  
- **Hikari active = 30** 플랫: 풀 **전부 사용 중**.  
- **Hikari pending ~700**: 커넥션을 못 받은 요청이 **풀 큐에 대량 적체**됐다는 뜻으로, **pool 30(이전 런) pending ~25**와 **질적으로 다른 장애 수준**이다.  
- **대기열 ~800**: 부하 모델과 맞물린 업무 게이지.  
- **JVM live threads ~30–32**: **800 VU**에도 플랫폼 스레드가 거의 늘지 않음 → **VT가 “스레드 수” 관점에서는 기대대로 동작**했다고 말할 수 있다.

### 9.4 해석 — 이 런이 **유의미한가?**

**유의미하다.** 다만 의미는 “**VT 덕에 더 빨라졌다**”가 아니라, **“한계가 어디로 옮겨졌는지”**가 선명해진다.

1. **VT 효과(증명 가능한 부분)**  
   동시에 800명 분의 시나리오를 돌리는데 **`jvm_threads_live_threads`가 ~32**에 머문다는 것은, **OS 스레드 포화로 요청을 못 받는 그림**이 아니라는 증거다. 면접에서는 “**가상 스레드는 I/O 대기를 저렴하게 겹쳐 쌓는다**”고 말하고, **그래프로 ‘스레드 폭증 없음’**을 보여 줄 수 있다.

2. **VT가 해결하지 못한 것(더 중요한 결론)**  
   DB 커넥션은 **여전히 30개**다. VT가 동시에 **기다릴 수 있는 논리 작업 수**를 늘리면, 그만큼 **같은 30슬롯을 두고 경쟁하는 쪽**으로 작업이 몰릴 수 있다. 그 결과 **`pending`이 ~700까지 치솟는 패턴**은 “**스레드 한계는 풀렸는데, DB 풀 큐가 새 병목**”이라는 전형적인 **병목 이동**으로 읽는 것이 타당하다.

3. **지연·에러가 악화된 이유(해석 프레임)**  
   **p95 16 s**, **HTTP 실패 2.62%**, **순번 조회 체크 실패**는 “서버가 정상 응답을 못 주거나(5xx/타임아웃), 클라이언트가 끊긴다” 쪽으로 이어질 수 있다. **풀 30(VT 이전)에서는 에러 0%**였다는 점과 대비되므로, **VT ON 런만으로 SLO를 개선했다고 쓰면 거짓**이다. 대신 **“VT 이후에야 DB 풀 큐잉이 메트릭으로 폭발적으로 드러났다”**는 식으로 쓰면 정직하다.

4. **포폴에서 쓸 한 문장**  
   “**Virtual Thread는 플랫폼 스레드 수를 억제했지만, Hikari max=30인 한 DB 커넥션은 물리 상한이라 `pending`이 폭증했고, 그 결과 p95·에러율이 악화됐다. 따라서 다음 튜닝은 풀 크기만이 아니라 DB/RDS 용량·쿼리·앱 인스턴스 수와 함께 봐야 한다.**”

### 9.5 다음 실험(선택)

- **같은 커밋·같은 데이터**에서 **VT OFF ↔ ON**을 **연속**으로 돌려 표를 맞춘다.  
- **MySQL `Threads_running` / CPU** 스크랩을 같은 대시보드에 올린다.

---

## 10. 코드 변경 — `queue/status` 잔여석 집계 캐시 (실측)

**문제**: `GET /api/queue/status`가 폴링마다 `SeatService`로 **DB 3회 + Redis** 잔여석 집계를 반복해, k6 고빈도 폴링 시 **Hikari `pending`**이 커지는 직접 원인이었다.

**조치** (동일 부하 k6로 **전·후** 비교):

| 구분 | 내용 |
|------|------|
| 캐시 | **Redis** `availableSeatCount`, TTL **2초** (`RedisConfig` + `spring.cache.type=redis`) |
| 적용 경로 | `countAvailableSeatsForQueueStatus(concertId)` — **`QueueController.status`만** |
| 비적용 | `POST /api/queue/enter`의 즉시 입장 판단은 **`countAvailableSeatsForDecision`** (캐시 없음) |
| 무효화 | 홀드 생성/취소(`HoldService`), 예약 확정 커밋 후(`ReservationConfirmedEventListener`), 홀드 만료 배치(`HoldCleanupScheduler`), 환불로 좌석 복구(`ReservationService.cancelReservationForRefund`)에서 `evictAvailableSeatCount(concertId)` |

**실측 비교** (동일 `queue-flow.js`, **pool 30 + VT ON**, VU 800 — **캐시 적용 커밋 전후**):

| 지표 | 변경 전 (§9.2, 캐시 없음) | 변경 후 (최신 런, §4·아래 Grafana) |
|------|---------------------------|-------------------------------------|
| k6 `http_req_duration` p(95) | **16.02 s** | **452.61 ms** |
| k6 `http_req_failed` | **2.62%** | **0.00%** |
| k6 `http_reqs` 합성 RPS | **~145/s** | **~853/s** |
| Hikari `pending` 피크 (Grafana) | **~700** 근방 | **0** (구간 전체 0에 가깝게 유지) |
| Hikari `active` 피크 (Grafana) | **30** (풀 포화) | **~13** 근방 |
| JVM live threads (Grafana) | **~30–32** | **~31** |
| Grafana HTTP p95 (서버 히스토그램) | **~19 s** 구간 | **~0.55 s** 피크 근방 |
| 체크 | 순번 조회 다수 실패 | **대기열 진입 201 / 순번 조회 200**, checks **100%** |

*k6 참고*: 동일 런에서 `http_req_duration` **max ≈ 33 s** tail이 있을 수 있어, **p95·에러율**과 함께 “꼬리”를 면접에서 언급하면 균형 잡힌다.

**한 줄 결론 (포폴용)**  
소스에서 **status 전용 경로만** 잔여석 집계를 **짧은 TTL Redis 캐시**로 감싸고, 좌석 변동 시 **evict**로 정합성을 맞추면, **동일 풀·동일 VT**에서도 **DB 커넥션 대기(pending)·종단 p95·처리량**이 함께 개선될 수 있다(특히 **캐시 없이 VT만 켰을 때** `pending`이 폭주하던 런 대비).

### Grafana 6패널 (캐시 적용 런)

`../portfolio/images/queue-flow-pool30-vt-cache-run-grafana-6panel.png` — 위 §4 마지막 행·k6 요약과 **동일 런** 캡처.

![](../portfolio/images/queue-flow-pool30-vt-cache-run-grafana-6panel.png)

---

## 11. `db-read.js` (아직 미실행 시)

`queue-flow.js`와 **별도 스크립트**다. 아래 표는 나중에 **동일 표 형식**으로만 채우면 된다.

| 설정 | VU | k6 p95 | 에러 | 메모 |
|------|-----|--------|------|------|
| A/B/C | | | | 동일 프레임으로 채운다 |

---

## 부록 — 도메인 knee

좌석 **홀드·락**은 `load-tests/seats-hold.js`와 락 관련 메트릭(`docs/monitoring.md`)으로 별도 한 페이지를 권장한다.

---

## 관련 파일

- `load-tests/README.md` — k6 env 전체
- `my-docs/load-test-guide.md` — 실행 커맨드 요약
