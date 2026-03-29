# k6 부하 테스트 (`load-tests/`)

[k6](https://k6.io/)로 시나리오별로 트래픽을 나눠 두었다. **한 스크립트가 모든 병목을 동시에 재현하지는 않는다.** Grafana/Prometheus에서 보는 패널(URI RPS, 대기열 게이지, 락 실패 카운터 등)과 맞추려면 시나리오를 골라 돌리거나 병행하는 편이 낫다. 커스텀 메트릭 요약은 `docs/monitoring.md`를 참고한다.

## 공통

| 환경 변수 | 설명 |
|-----------|------|
| `BASE_URL` | 앱 베이스 URL (기본 `http://localhost:8080`) |
| `CONCERT_ID` | 대상 공연 ID (기본 `1`) |
| `TEST_USER` / `TEST_PASS` | 세션이 필요한 시나리오용 폼 로그인 계정 |
| `K6_CACHE_HOT_PEAK_VU` | `cache-hot-read.js` 최대 VU (기본 `150`; 소형기는 더 낮춰도 됨) |

- **`lib/common.js`**: `baseUrl()`, `concertId()`, `formLogin()` — 인증 필요 스크립트에서 공통 사용.
- **VU당 로그인 1회**: `db-read.js`, `seats-hold.js`, `full-flow.js`는 동일 계정을 여러 VU가 쓸 때 **매 요청마다 로그인하면 세션이 무효화**될 수 있어, VU 시작 시 한 번만 로그인한다.

실행 예:

```bash
k6 run -e BASE_URL=https://app.example.com:8080 -e CONCERT_ID=1 load-tests/api-health.js
k6 run -e BASE_URL=... -e CONCERT_ID=1 -e TEST_USER=u -e TEST_PASS=p load-tests/full-flow.js
```

---

## 1. `api-health.js`

**역할**: 앱 전반 가용성·가벼운 공개 API를 동시에 두드린다.

- **`GET /actuator/health`**: Actuator 헬스.
- **`GET /api/queue/required?concertId=`**: 대기열 필요 여부(설정·Redis 대기 인원 조회 포함).

**모니터링과의 연결**: 여기서만 p95·에러가 먼저 깨지면 스레드 풀·공통 다운스트림·기본 가용성 쪽을 의심한다. `queue/required`만 이상하면 해당 경로나 Redis를 본다.

**특징**: 인증 불필요. VU는 상대적으로 낮게 잡혀 있다.

---

## 2. `queue-flow.js`

**역할**: **대기열만** 집중 부하 — 진입 후 `status` 폴링까지(입장 허용 시 종료). 좌석·홀드·결제는 포함하지 않는다.

- **`POST /api/queue/enter`**
- **`GET /api/queue/status`** (반복, `MAX_STATUS_POLLS` / `POLL_SLEEP_SEC` 조정 가능)

**모니터링과의 연결**: `ticketing_queue_waiting_count`, 큐 관련 HTTP RPS·지연이 오르는 패턴을 만들기 좋다. `full-flow`만 돌리면 큐가 활성화되지 않으면 이 구간 트래픽이 거의 없을 수 있다.

**특징**: 인증 없이 큐 API만 사용. VU·플래토가 크게 설정되어 있다.

---

## 3. `cache-hot-read.js`

**역할**: **`GET /api/queue/count` 고빈도** — 대기 인원 조회 한 종류만 반복한다. 구현상 Redis ZSet `size` 읽기에 가깝다(Spring HTTP 응답 캐시라기보다 **Redis 핫 읽기 경로**).

**모니터링과의 연결**: Prometheus `http_server_requests_*`에서 `/api/queue/count` URI의 **RPS·지연**을 올리기 위한 전용 시나리오. `queue-flow.js`와 겹쳐 보면 **“읽기만 폭주”** vs **“진입+폴링”**을 나눠 볼 수 있다.

**특징**: 인증 불필요. 이터레이션당 `COUNTS_PER_ITERATION`번 연속 호출로 RPS를 키운다.

**Grafana에 안 찍힐 때**: (1) **앱이 정상 기동·DB 연결**인지 먼저 본다(`actuator/health`, 컨테이너 로그). (2) **k6 → 앱 TCP**가 되는지 curl로 확인(SG 등). (3) 그다음 **과부하** 여부: 피크 VU·`COUNTS_PER_ITERATION`가 크면 `connection refused` 등으로 HTTP가 안 들어가 메트릭이 비어 보일 수 있다 → `-e K6_CACHE_HOT_PEAK_VU=80` 등으로 낮춘다. Prometheus에서 `count by (uri) (http_server_requests_seconds_count)` 로 `uri` 라벨도 확인한다.

---

## 4. `db-read.js`

**역할**: **DB 읽기 위주** API를 한 이터레이션에서 연속 호출한다.

- 로그인 후 `GET /api/concerts/counts`, `GET /api/concerts?past=false`, `GET /api/concerts/{id}/seats`

**모니터링과의 연결**: 이 스크립트만 돌릴 때 p95가 먼저 나빠지면 커넥션 풀·슬로우 쿼리·DB CPU/IO를 의심한다. 다른 시나리오는 괜찮은데 이것만 나쁘면 읽기 쿼리·인덱스를 우선 본다.

**특징**: 쓰기 락 경합은 상대적으로 적고 읽기 한계를 밀어보기에 유리하다.

---

## 5. `seats-hold.js`

**역할**: 로그인 → 좌석 목록 → **홀드 생성** → **`DELETE`로 홀드 취소**를 반복한다. 예약·결제 확정은 없다.

**모니터링과의 연결**: `ticketing_lock_acquire_failures_total`과 홀드 API 지연이 함께 오르면 좌석 락·Redis 경합 힌트. 좌석 조회만 느리면 DB/풀 쪽을 본다.

**특징**: 동일 공연·좌석 풀에 VU를 올리면 홀드 경합을 보기 좋다. 좌석이 없으면 잠시 sleep 후 스킵한다.

---

## 6. `full-flow.js`

**역할**: **E2E** — `queue/required`가 true일 때만 대기열 진입·폴링 → 좌석 조회 → 홀드 → **포인트 결제**(`POINT` 고정) → 완료까지.

**모니터링과의 연결**: 여러 계층이 한꺼번에 묶여 나오므로 knee 탐색용으로 쓰기 좋다. 대시보드만 볼 때는 다음을 기억한다.

- **대기열 인원**: 설정상 `required`가 거의 false면 0에 가깝게 보이는 것이 정상. 큐 패널을 채우려면 `queue-flow.js` 병행 또는 activation threshold 조정이 필요할 수 있다.
- **캐시( count ) RPS**: 기본은 `queue/count`를 거의 호출하지 않는다. 같은 스크립트로 보강하려면 `-e K6_EXTRA_QUEUE_COUNT=15`처럼 지정한다.

**특징**: 카드 등 외부 위젯 결제는 k6로 자동화하지 않는다. 스테이지·threshold는 환경에 맞게 `full-flow.js` 상단에서 조정한다.
