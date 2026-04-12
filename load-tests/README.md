# k6 부하 테스트 (`load-tests/`)

[k6](https://k6.io/)로 시나리오별로 트래픽을 나눠 두었다. **한 스크립트가 모든 병목을 동시에 재현하지는 않는다.** Grafana/Prometheus에서 보는 패널(URI RPS, 대기열 게이지, 락 실패 카운터 등)과 맞추려면 시나리오를 골라 돌리거나 병행하는 편이 낫다. 커스텀 메트릭 요약은 `docs/monitoring.md`를 참고한다.

## 공통

**부하 곡선은 모든 `*.js`가 동일 패턴**이다: 단계적으로 VU를 올린 뒤 **`K6_PEAK_HOLD` 동안 최대 VU(피크)를 유지**하고 램프다운한다. 숫자를 파일에서 바꾸지 않고 **환경 변수만** 조절하면 된다. 정의는 **`lib/stages.js`** 참고.

| 환경 변수 | 설명 |
|-----------|------|
| `BASE_URL` | 앱 베이스 URL (기본 `http://localhost:8080`) |
| `CONCERT_ID` | 대상 공연 ID (기본 `1`) |
| `TEST_USER` / `TEST_PASS` | JWT 로그인(`POST /api/auth/login`)이 필요한 시나리오용 계정 |
| `K6_PEAK_VU` | **최대 동시 VU**(피크). 미설정 시 스크립트마다 다른 기본값(아래 각 절). |
| `K6_PROFILE=stress` | **한계(knee) 관측**: threshold를 거의 막지 않아 에러가 나도 시나리오가 끝까지 진행된다. |
| `K6_WARM_DURATION` / `K6_MID_DURATION` / `K6_CLIMB_DURATION` / `K6_PEAK_HOLD` / `K6_RAMP_DOWN` | 각 단계 지속 시간(예: `30s`, `2m`, `90s`). 미설정 시 스크립트별 기본. |
| `K6_WARM_VU` / `K6_MID_VU` / `K6_PREPEAK_VU` | 중간 단계 타깃 VU를 직접 지정(선택). 미설정 시 `K6_PEAK_VU` 비율로 자동 계산. |

**시나리오별 선택 변수** (해당 스크립트에서만 읽음):

| 변수 | 스크립트 | 설명 |
|------|-----------|------|
| `K6_LOOP_SLEEP_SEC` | `db-read.js` | 이터레이션 끝 sleep 초 (기본 `0.2`) |
| `K6_ITER_SLEEP_SEC` | `seats-hold.js` | 이터레이션 간 sleep 초 (기본 `0.3`) |
| `K6_HOT_SEAT_ID` | `seats-hold.js` | 지정 시 **항상 그 좌석 ID**로만 홀드 시도(랜덤 분산 방지, 락 실패율 관측용) |
| `K6_FLOW_SLEEP_SEC` | `full-flow.js` | 이터레이션 간 sleep 초 (기본 `0.35`) |
| `K6_EXTRA_QUEUE_COUNT` | `full-flow.js` | 결제 후 `queue/count` 추가 호출 횟수 (기본 `0`) |
| `K6_QUEUE_MAX_POLL` / `K6_QUEUE_POLL_SLEEP_SEC` | `queue-flow.js`, `full-flow.js` | 큐 폴링 최대 횟수·간격(초) |

- **`lib/common.js`**: `baseUrl()`, `concertId()`, `jwtLogin()`, `authHeaders()`.
- **VU당 로그인 1회**: `db-read`·`seats-hold`·`full-flow`는 모듈 변수에 토큰을 두고 재사용한다.

실행 예:

```bash
k6 run -e BASE_URL=https://app.example.com:8080 -e CONCERT_ID=1 load-tests/api-health.js
k6 run -e BASE_URL=... -e CONCERT_ID=1 -e TEST_USER=u -e TEST_PASS=p load-tests/full-flow.js
# 피크만 키워 한계 보기 (모든 스크립트 동일)
k6 run -e BASE_URL=... -e CONCERT_ID=1 -e K6_PEAK_VU=2000 -e K6_PROFILE=stress load-tests/queue-flow.js
```

---

## 1. `api-health.js`

**역할**: 앱 전반 가용성·가벼운 공개 API를 동시에 두드린다.

- **`GET /actuator/health`**: Actuator 헬스.
- **`GET /api/queue/required?concertId=`**: 대기열 필요 여부(설정·Redis 대기 인원 조회 포함).

**모니터링과의 연결**: 여기서만 p95·에러가 먼저 깨지면 스레드 풀·공통 다운스트림·기본 가용성 쪽을 의심한다. `queue/required`만 이상하면 해당 경로나 Redis를 본다.

**특징**: 인증 불필요. 기본 `K6_PEAK_VU` 미설정 시 피크 **50**.

---

## 2. `queue-flow.js`

**역할**: **대기열만** 집중 부하 — 진입 후 `status` 폴링까지(입장 허용 시 종료). 좌석·홀드·결제는 포함하지 않는다.

- **`POST /api/queue/enter`**
- **`GET /api/queue/status`** (반복, `K6_QUEUE_MAX_POLL` / `K6_QUEUE_POLL_SLEEP_SEC`)

**모니터링과의 연결**: `ticketing_queue_waiting_count`, 큐 관련 HTTP RPS·지연이 오르는 패턴을 만들기 좋다. `full-flow`만 돌리면 큐가 활성화되지 않으면 이 구간 트래픽이 거의 없을 수 있다.

**특징**: 인증 없이 큐 API만 사용. **기본값은 단일 앱 서버 “용량 봉투” 탐색용**: 곡선 **약 90초**(10+10+15+45+10), 기본 피크 **200** VU, 폴링 기본 **0.08s** (`K6_QUEUE_POLL_SLEEP_SEC`). 이전처럼 강한 스트레스(예: 피크 800·폴링 0.005s)는 **env로만** 지정한다. 파일 상단 주석에 동일 커맨드 예시 있음.

---

## 3. `db-read.js`

**역할**: **DB 읽기 위주** API를 한 이터레이션에서 연속 호출한다.

- 로그인 후 `GET /api/concerts/counts`, `GET /api/concerts?past=false`, `GET /api/concerts/{id}/seats`

**모니터링과의 연결**: 이 스크립트만 돌릴 때 p95가 먼저 나빠지면 커넥션 풀·슬로우 쿼리·DB CPU/IO를 의심한다. 다른 시나리오는 괜찮은데 이것만 나쁘면 읽기 쿼리·인덱스를 우선 본다.

**특징**: 쓰기 락 경합은 상대적으로 적음. 기본 피크 **120** VU.

---

## 4. `seats-hold.js`

**역할**: 로그인 → 좌석 목록 → **홀드 생성** → **`DELETE`로 홀드 취소**를 반복한다. 예약·결제 확정은 없다.

**모니터링과의 연결**: `ticketing_lock_acquire_failures_total`과 홀드 API 지연이 함께 오르면 좌석 락·Redis 경합 힌트. 좌석 조회만 느리면 DB/풀 쪽을 본다.

**특징**: 동일 공연·좌석 풀에 VU를 올리면 홀드 경합을 보기 좋다. 좌석이 매우 많으면 랜덤이 분산되어 락 실패가 안 나올 수 있다 → `-e K6_HOT_SEAT_ID=<id>`로 한 좌석만 공유해 경합을 만든다. **`ticketing_lock_acquire_failures_total`은 `tryLock` 실패(429)만** 세므로, 경합은 **`ticketing_hold_conflict_total`** 또는 **`http_server_requests`의 POST `/api/holds` 409**로 보는 것이 맞다. 겹침을 키우려면 `-e K6_ITER_SLEEP_SEC=0` 과 `K6_PEAK_VU` 상향을 권장한다. 기본 피크 **60** VU. (핫 시트 미사용 시) `AVAILABLE`이 없으면 sleep 후 스킵.

---

## 5. `full-flow.js`

**역할**: **E2E** — `queue/required`가 true일 때만 대기열 진입·폴링 → 좌석 조회 → 홀드 → **포인트 결제**(`POINT` 고정) → 완료까지.

**모니터링과의 연결**: 여러 계층이 한꺼번에 묶여 나오므로 knee 탐색용으로 쓰기 좋다. 대시보드만 볼 때는 다음을 기억한다.

- **대기열 인원**: 설정상 `required`가 거의 false면 0에 가깝게 보이는 것이 정상. 큐 패널을 채우려면 `queue-flow.js` 병행 또는 activation threshold 조정이 필요할 수 있다.
- **캐시( count ) RPS**: 기본은 `queue/count`를 거의 호출하지 않는다. 같은 스크립트로 보강하려면 `-e K6_EXTRA_QUEUE_COUNT=15`처럼 지정한다.

**특징**: 카드 등 외부 위젯 결제는 k6로 자동화하지 않는다. 기본 피크 **140** VU. 곡선·피크는 `K6_PEAK_VU` 등 공통 env로 조절.

---

## 6. `jwt-scenarios.js` (기능 검증용)

**역할**: JWT 대표 경우의수(정상/만료 조합/블랙리스트/탈취 재사용/헤더 누락/서명 위조)를 **1회 시퀀스**로 검증한다.

- 포함: `S1,S2,S3,S4,S5,S6` + `E2,E4,E5`
- 부하 곡선 없음(기능 시나리오 전용)

필수 변수:

- `BASE_URL`
- `TEST_USER`, `TEST_PASS`

선택 변수:

- `TEST_USER_2`, `TEST_PASS_2` (subject mismatch 테스트)
- `K6_WAIT_EXPIRY_MAX_SEC` (기본 120초, 만료 대기 최대치)
- `JWT_PROTECTED_PATH` (기본 `/api/concerts/counts`)

실행 예:

```bash
k6 run -e BASE_URL=http://localhost:18080 -e TEST_USER=u -e TEST_PASS=p load-tests/jwt-scenarios.js
```

만료 케이스(`S2/S3/S4`)는 토큰 TTL이 길면 대기 시간이 커지므로, 테스트 환경에서 JWT TTL을 짧게 설정하는 것을 권장한다.
JWT 시나리오 전용 `application-test` 프로파일은 **`server.port=18080`** 과 짧은 TTL(`access=20s`, `refresh=70s`)만 둔다.

```bash
# 서버 (JWT 시나리오용 test 프로파일 → 18080)
./gradlew bootRun --args='--spring.profiles.active=test'

# k6 (만료까지 최대 180초까지 기다림)
k6 run -e BASE_URL=http://localhost:18080 -e TEST_USER=u -e TEST_PASS=p -e K6_WAIT_EXPIRY_MAX_SEC=180 load-tests/jwt-scenarios.js
```

원격 k6 서버에서는 `BASE_URL`을 앱 인스턴스의 **프라이빗 IP:18080** 등으로 맞춘다.

`K6_WAIT_EXPIRY_MAX_SEC`는 "토큰 만료를 기다릴 최대 시간(초)"이다. 실제 남은 만료 시간이 이 값보다 크면 해당 케이스를 실패(스킵) 처리해 테스트가 끝없이 길어지는 것을 막는다.
