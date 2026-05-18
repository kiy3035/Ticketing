# 📌 프로젝트 개요

**"단 1초의 트래픽 폭주 속에서도 무결한 좌석 선점을 보장하는 콘서트 예매 시스템"**

콘서트 오픈 순간의 **대규모 트래픽 폭주**와 하나의 자원을 두고 싸우는 **동일 좌석 동시 선점**, 이 두 가지 문제를 분산 락과 이벤트 기반 아키텍처로 해결한 프로젝트.

- **트래픽 폭주**: 인기 공연 오픈 순간 평소의 수십~수백 배 요청이 단시간에 몰리는 병목 현상
- **좌석 선점 경쟁**: 한정된 좌석을 두고 다수의 사용자가 경합하며 단 하나의 데이터 오차도 허용하지 않는 데이터 무결성 문제

이 프로젝트는 단순 기능 구현을 넘어 **'대규모 부하 테스트를 통해 데이터로 시스템의 성능과 행동을 증명하는 시스템'** 구축을 목표로 설계·구현·운영했다.

> 🛠️ **AI 협업 방식 안내**
> 본 프로젝트는 Claude·Cursor를 적극 활용한 **바이브 코딩** 방식으로 진행했다. 코드 작성·문서화·가설 제시 단계는 AI와 협업했고, 측정 실행·재현성 검증·트레이드오프 판단·최종 의사결정은 본인이 직접 수행했다. 본 문서의 수치는 모두 실측 결과이며, 어떤 영역을 AI에 맡기고 어떤 영역을 직접 판단했는지는 아래 `AI 협업 분담` 섹션에 명시했다.

-- 시스템 아키텍처 섹션(사진)

-- CI/CD 파이프라인 섹션(사진)

---

# 🤝 AI 협업 분담

SI 출신 백엔드 개발자로서, IT 서비스업 트랜지션 과정에서 AI 도구를 의도적으로 활용했다. 면접관에게 변명이 아닌 자기 인식으로 공유한다.

### AI(Claude/Cursor)에 맡긴 영역
- **반복적 보일러플레이트 작성**: DTO·Controller·Repository 스캐폴드, Bean Validation 어노테이션
- **Lua 스크립트 초안 작성**: SETNX·EXISTS·ZADD를 결합한 Lua 문법 초안 (이후 본인이 원자성 가설로 재검토)
- **문서·주석 1차 작성**: README, ADR 초안, JavaDoc 1차 표현
- **부하 테스트 가설 후보 제시**: "pool을 늘려보자", "Virtual Thread를 켜보자" 등 시도 후보군 나열
- **에러 메시지·시나리오 케이스 발산**: 놓친 엣지 케이스 후보 제시

### 본인이 직접 수행한 영역
- **아키텍처 의사결정**: Redisson 미도입(단일 Redis 전제), nginx 단독(ALB 미사용), Saga REQUIRES_NEW, JWT 블랙리스트 전략
- **임계치·TTL 산정 근거**: 락 TTL 3초(정상 흐름 1초 + 3배 마진), 캐시 TTL 2초(잔여석 근사 허용 가능 범위), 풀 30(부하 테스트 실측 도출)
- **부하 테스트 시나리오 설계 및 실행**: k6 스크립트 4종 작성·실행, 4회차 시행착오 직접 디버깅
- **가설 기각 판단**: pool→VT 두 가설 기각 후 폴링 빈도가 진짜 병목이라는 결론, ablation 매트릭스 작성으로 nginx 튜닝 단독은 악화임을 증명
- **재현성 검증**: 모든 핵심 측정에서 최소 2회 반복, Part A는 20회 반복
- **트레이드오프 명시**: 단일 Redis 전제 한계, VT ablation 미완성 한계, 5% 미만 SLO에 active HC 필요 등 본문에 명시

이 분담의 핵심은 **"AI가 만든 코드를 그대로 신뢰하지 않고, 측정으로 검증한 뒤 본인 이름으로 채택했는가"**다.

---

# 🛠️ 트러블슈팅 및 검증 사례

### [핵심 가치]
- **데이터 정합성 검증**: 분산 락을 통해 멀티 인스턴스 환경에서 좌석 중복 선점 가능성을 0%로 통제함을 통계적으로 증명
- **데이터 기반 진단**: 부하 테스트를 수행해 시스템 임계점과 병목 구간을 정량적으로 식별·개선

---

## 🔒 Part A. 멀티 인스턴스 환경의 동시성 제어 (Redis SETNX 분산 락)

### 1. 문제 정의
- **환경**: 애플리케이션 서버 2대(Multi-Instance) + Nginx 로드밸런서
- **과제**: 동일 좌석에 대규모 동시 선점 요청이 수렴할 때 정확히 1개의 요청만 성공
- **검증 방향**: 코드 레벨의 낙관적 예측을 배제, 멀티 스레드 부하 테스트로 통계적 증거 확보

### 2. 아키텍처 설계 및 해결

#### Redis SETNX + 고유 UUID 토큰 락
좌석 단위 락 키 `lock:seat:{seatId}` + 매 호출마다 발급되는 UUID 토큰 조합. 토큰은 unlock 시 소유자 검증에 사용해 **타 요청이 만료 후 갱신된 락을 실수로 해제하는 사고를 차단**한다.

```java
// RedisLockService.java
public Optional<String> tryLock(String key, Duration ttl) {
    String token = UUID.randomUUID().toString();
    Boolean success = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
    return Boolean.TRUE.equals(success) ? Optional.of(token) : Optional.empty();
}

// 토큰이 일치할 때에만 락을 해제하는 Lua 스크립트.
// GET/DEL을 단일 명령으로 묶어 다른 쓰레드가 새로 잡은 락을 실수로 지우는 상황 방지.
private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
    Long.class
);
```

#### 비즈니스 타임아웃 기준 TTL(3초) 산정
정상 프로세스(Redis 호출 + DB 반영)의 TAT가 1초 미만임을 측정으로 확인. 3배 마진으로 락 보유자의 비정상 종료 시 자원 고립을 방지하면서 정상 처리 도중 락이 먼저 만료되는 'Lock 릴리즈 현상'도 차단했다. 외부화 위치는 `application.properties`:

```properties
# 정상 흐름(Redis 4회 + DB 1회)이 1초 내 종료되는 것 기준 3배 여유
ticketing.lock.ttl-seconds=3
```

#### Lua 스크립트로 좌석 상태 전이 원자화
좌석→토큰, 토큰→홀드 정보, 만료 ZSET을 단일 Lua 트랜잭션으로 처리. 중간 연산 개입 가능성을 원천 배제한다.

```lua
-- HoldStore.CREATE_SCRIPT
if redis.call('EXISTS', KEYS[1]) == 1 then
    return 0
end
redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])  -- 좌석→토큰
redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[2])  -- 토큰→홀드 정보
redis.call('ZADD', KEYS[3], ARGV[4], ARGV[3])       -- 만료 ZSET
return 1
```

#### 트레이드오프: Redisson 미도입
단일 Redis 인스턴스 환경에서 분산 합의 알고리즘(Redlock) 기반 Redisson 도입은 불필요한 복잡도와 오버헤드를 유발한다고 판단. **UUID 토큰 + Lua 원자 연산** 조합만으로 단일 노드 무결성 확보가 가능하다고 결정.

### 3. 검증 시나리오

-- 검증 시나리오 표(기존 유지)

### 4. 결과

-- 결과 표(기존 유지)

20회 전부 201 = 1건. Redis가 단일 잠금 저장소이므로 요청이 nginx를 거쳐 2대에 분산되어도 락의 원자성이 유지된다는 사실이 통계적으로 확인된다. 409·429 비율은 회차마다 달라지지만 두 응답 모두 중복 선점을 막는 올바른 동작이다 (락 단계에서 걸리면 429, 락 통과 후 이미 선점 상태 확인이면 409).

### 5. 부수 발견 — HoldController 응답 코드 버그

초기 검증에서 `201 체크`가 0건으로 잡혔지만 `http_req_failed` 역산 시 1건은 비실패. 추적 결과 `HoldController.createHold()`가 `ResponseEntity` 없이 `HoldResponse`를 직접 반환해 Spring이 기본 200 OK를 적용한 것이 원인. 한 줄로 수정:

```java
// HoldController.java
@PostMapping
@ResponseStatus(HttpStatus.CREATED)  // 부하 테스트 중 발견·추가
public HoldResponse createHold(Authentication authentication, @Valid @RequestBody HoldRequest request) {
    return holdService.createHold(request, authentication.getName());
}
```

응답 코드 분포까지 셈한 덕에 우연히 잡힌 결함.

### 6. 남은 한계 (정직성 영역)

Redisson 미도입은 현재 단일 Redis 인스턴스 전제에서만 유효. Sentinel/Cluster로 전환하면 Redlock이 필요한 시점이 오고, 그때는 라이브러리 선택을 재검토해야 한다. 현재 검증은 **단일 Redis 환경의 통계적 증거**이지, 모든 분산 환경에서 안전임을 증명한 것은 아니다.

> 💭 **느낀점**
> - 정확성은 한 번 통과가 아니라 20번 독립 시행 전부 통과가 의미를 가진다. 단일 실행은 운으로 통과 가능
> - k6 기본 동작은 4xx를 모두 실패로 집계. 이 시나리오에서는 409·429가 정상 응답이라 `http_req_failed: rate<1`로 임계값을 명시. 측정 도구의 디폴트가 도메인에 맞지 않으면 그 디폴트를 따져봐야 한다
> - 통과/실패만 보지 않고 응답 코드 분포까지 셈한 덕에 200 OK 버그까지 발견

---

## ⭐ Part B. 부하 테스트로 본 시스템 진단·개선

### 🧰 부하 테스트 인프라 구축 — 측정 가능한 시스템 만들기

부하 테스트 본 사례 들어가기 전에, **측정 인프라 자체를 구축하는 과정에서 겪은 어려움**을 먼저 정리한다. 측정 결과만 보여주면 "그래서 어떻게 측정했나"가 빠지므로, 도구 셋업 자체도 트러블슈팅 사례로 다룬다.

#### 1. Grafana에 p95가 안 찍힘 — Prometheus 히스토그램 누락

초기에 `http_req_duration` p95를 Grafana 패널에 띄우려 했으나 그래프가 평탄선만 표시. `histogram_quantile()`이 요구하는 `*_bucket` 시리즈가 Prometheus에 없는 것이 원인. Spring Boot Actuator는 기본적으로 percentile 메트릭만 발행하고 bucket 시리즈는 옵트인이다.

```properties
# application.properties
# HTTP 지연 p95 등: Prometheus에 *_bucket 시리즈를 보내야 histogram_quantile 사용 가능
management.metrics.distribution.percentiles-histogram.http.server.requests=true
```

이 한 줄을 추가한 뒤에야 Grafana에서 `histogram_quantile(0.95, sum by(le)(rate(http_server_requests_seconds_bucket[1m])))` 쿼리가 동작.

#### 2. 부하 시 Kafka 헬스 인디케이터가 60초 타임아웃

`/actuator/health` 호출이 부하 중 60초에 한 번씩 멈추는 현상 발견. Spring Boot 기본 Kafka 헬스 인디케이터가 broker `metadata` 호출을 동기로 수행하는데, 부하 상황에서 응답 지연이 헬스 체크 전체를 막아 nginx의 헬스 체크 응답까지 영향. 진단 후 비활성화:

```properties
# 부하 시 Kafka indicator가 60초 타임아웃 유발 → 헬스 응답 막힘
management.health.kafka.enabled=false
```

#### 3. Prometheus가 app2를 모르고 있었다 (Phase 8 베이스라인 측정 중 발견)

페일오버 baseline 1회차 측정 도중 server1을 docker kill하자 **Grafana 패널이 모두 공백**. 그런데 k6 합산 RPS는 끊기지 않음. 두 신호의 불일치가 단서. 확인해보니 `prometheus.yml scrape_configs`에 server1 IP만 등록되어 있었다. server2 추가 후 1회차 폐기, 재측정.

→ **측정 인프라가 측정 대상의 가용성에 의존하면 가장 필요한 순간에 데이터가 사라진다.** 이후 부하 테스트 체크리스트에 "타겟이 모든 인스턴스를 잡고 있는가" 항목 추가.

#### 4. 비즈니스 메트릭 커스텀 등록

서버 헬스만으로는 도메인 행동을 추적할 수 없어 핵심 지점에 Micrometer 커스텀 카운터 직접 등록:

```java
// HoldService.java — 생성자에서 Counter 등록
this.holdCreatedCounter = Counter.builder("ticketing_hold_created_total")
    .tag("status", "success")
    .description("Number of seat holds created successfully")
    .register(meterRegistry);

this.lockFailureCounter = Counter.builder("ticketing_lock_acquire_failures_total")
    .tag("operation", "hold")
    .description("Number of lock acquire failures when creating hold")
    .register(meterRegistry);

this.holdConflictCounter = Counter.builder("ticketing_hold_conflict_total")
    .tag("reason", "seat_already_held_redis")
    .description("Hold rejected: seat already held in Redis (Lua createHold false)")
    .register(meterRegistry);
```

홀드 성공·락 경합·Lua 중복 검출을 각각 별도 카운터로 분리. Grafana에서 "락 실패가 늘면서 진입은 줄었나"를 한 패널에서 확인 가능해졌고, 부하 테스트 결과 해석의 근거 데이터로 작용.

> 💭 **측정 인프라에서 배운 것**
> - 부하 테스트는 k6 한 줄 실행이 아니라 **k6 → Prometheus → Grafana** 세 도구가 모두 같은 사실을 가리켜야 신뢰할 수 있다
> - 헬스 체크 자체가 부하 원인이 될 수 있다 (Kafka indicator 사례)
> - "왜 이 그래프가 비어 있지?" 질문이 가장 먼저 떠올라야 한다 (Prometheus scrape 누락 사례)

---

### B-1. p95가 떨어지지 않는다 — 풀과 스레드를 다 바꿔도 안 풀리던 문제

초기 측정: VU 800으로 큐 폴링 API → **p95 = 1.93초** (사용자 입장에서 "고장났다" 수준).

Grafana 신호: `hikaricp_connections_active`가 10에 평탄, `hikaricp_connections_pending`이 톱니파로 170까지 반복. 풀이 좁다고 판단.

#### 첫 가설 — Hikari pool 10 → 30

-- 표(기존 유지)

p95는 80ms만 줄고 RPS는 오히려 감소. 풀을 3배로 늘렸는데 사실상 차이가 없음.

#### 두 번째 가설 — Virtual Thread

```
JVM live threads: 225 → 30   ▼ 87%
p95:              1.85s → 2.06s   ▲ 악화
RPS:              386 → 376/s
```

스레드는 줄었지만 p95는 오히려 올라감. 두 가설 모두 빗나감.

#### 두 그래프 불일치가 단서였다

가설을 폐기하고 Grafana를 다시 봤다. **pending은 줄었는데 p95는 안 줄었다** — 두 그래프가 같이 안 움직였다는 사실이 결정적.

> pending이 줄어도 p95가 안 줄면, 커넥션 자체는 병목이 아니다.

폴링 구조를 다시 짚어보니: `GET /api/queue/status` 1회마다 DB 쿼리 3회(`countByConcertId`, `countByConcertIdAndStatus`, `findSeatIdsByConcertId`) 실행. 풀을 늘리면 더 많은 폴링이 동시에 풀 슬롯을 점유하고 그대로 DB로 향한다. 풀이 좁아서 큐에 갇혀 있던 폴링이 풀을 넓히면 그대로 DB로 풀려나가는 구조. **진짜 병목은 DB 쿼리 빈도.**

#### 해결 — 잔여석 캐시

도메인 판단: 잔여석 수는 1~2초 늦은 근사값이어도 사용자 경험상 충분. `SeatService.countAvailableSeatsForQueueStatus()`에 Redis 캐시 적용. 캐시가 깨져야 할 **6개 시점**(홀드 생성, 홀드 해제, 만료 정리, 예약 확정, 예약 환불, 좌석 추가)에 `@CacheEvict`.

```java
// SeatService.java
@Cacheable(cacheNames = CacheNames.QUEUE_STATUS_AVAILABLE_SEATS, key = "#concertId")
public long countAvailableSeatsForQueueStatus(Long concertId) {
    return computeAvailableSeats(concertId);  // DB 2 COUNT + Repository 조회
}

@CacheEvict(cacheNames = CacheNames.QUEUE_STATUS_AVAILABLE_SEATS, key = "#concertId")
public void evictQueueStatusAvailableSeats(Long concertId) {
    // 캐시 aspect만 사용
}
```

```properties
# application.properties — 외부화된 TTL
# GET /api/queue/status 의 availableSeatCount 집계 TTL(초). 홀드/예약/만료/좌석 추가 시 evict 병행
ticketing.cache.queue-status-available-seats-ttl-seconds=2
```

evict 호출 6개 지점(실제 코드 기준):
- `HoldService:146` — 홀드 생성 직후
- `HoldService:166` — 홀드 취소
- `HoldCleanupScheduler:107` — 만료 홀드 정리
- `ReservationConfirmedEventListener:41` — 예약 확정 이벤트
- `ReservationService:208` — 예약 환불
- `SellerService:188` — 좌석 추가

-- 표 (캐시 전/후/변화 수치, 기존 유지)

#### 남은 한계

캐시 후 환경은 `pool=30 + VT on` 한 조건만 측정. 같은 환경에서 VT off 비교는 진행하지 않음. 2.2배 RPS 증가는 캐시 효과가 맞지만, 그 안에 Virtual Thread가 얼마나 기여했는지는 이 데이터만으로 분리 불가. 사례 B-3에서 ablation을 제대로 한 것과 대비되는 미완성 측정.

> 💭 **느낀점**
> - 직관적 가설을 두 번 시도하고 두 번 다 틀린 것이 핵심 경험. 데이터 없이 느낌으로 풀을 60, 100까지 올렸으면 영영 못 풀었을 것
> - 결정적 신호는 두 그래프의 불일치. 한 그래프만 봤으면 못 잡았을 신호
> - 캐시는 만능이 아니라 도메인 판단의 결과. "잔여석 1~2초 근사 허용"이라는 비즈니스 조건이 없었으면 못 썼음

---

### B-2. 측정 도구가 측정 대상이 되어버린 — Knee Point를 찾는 과정

목표: VU=800 에러 0%, VU=1500 에러 3.41%. 그 사이 변곡점을 찾아 운영 SLO 기준 확보.

도구는 그대로지만 **시나리오 자체가 조작 가능한 부하 생성기**임을 깨닫는 시행착오.

| 회차 | 문제 | 원인 | 조치 |
|------|------|------|------|
| 1 | ramp 안 되고 한 번에 피크 | 기존 `queue-flow.js` stress profile이 step 정의 무시 | `knee-point.js` 신규 작성 |
| 2 | 진입 성공률 21%로 폭락 | `sleep(1)` 후 재시도 → 1000명이 동시에 재시도 → Rate Limiter가 공격으로 인식 → 429 retry storm | `sleep(5)` + 백오프 |
| 3 | 5xx 30% | `MAX_POLLS=1000` (4분) → 단계 사이 VU 600+ 누적 폴링 | `MAX_POLLS=300`으로 축소 |
| 4 | 측정 성공 | — | — |

최종 시나리오:

```javascript
// knee-point.js
export const options = {
  stages: [
    { duration: '1m',  target: 500  },  // 워밍업 — JVM warm + 안정 베이스라인
    { duration: '1m',  target: 800  },  // Phase 3에서 에러 0% 확인한 구간
    { duration: '1m',  target: 1000 },  // 탐색 구간
    { duration: '1m',  target: 1200 },  // 탐색 구간
    { duration: '1m',  target: 1500 },  // Phase 4에서 에러 3.41% 확인한 구간
    { duration: '30s', target: 0    },  // 쿨다운
  ],
  thresholds: {
    http_req_duration: ['p(95)<120000'],
    http_req_failed:   ['rate<1'],  // knee point 탐지용 — 에러가 나도 테스트 중단하지 않음
  },
};

// VU=1500 큐 드레인 60초 / poll 1회 ~235ms = 255회 필요 → 여유 포함 300
const MAX_POLLS = parseInt(__ENV.K6_QUEUE_MAX_POLL || '300', 10);
```

#### 4회차 결과

| 지표 | 값 |
|------|------|
| p95 | 494ms |
| 에러율 | 4.90% |
| RPS | 2,324/s |
| 진입 성공률 | 86% |

#### Knee Point 식별 — 두 독립 신호가 같은 시점을 가리켰다

1. **k6 클라이언트**: `WARN[0178] EOF` — 178초 시점부터 서버가 연결을 끊기 시작. 178초는 정확히 VU=1000→1200 전환 시점.
2. **Grafana 서버**: 같은 178초 구간부터 RPS 곡선이 평탄해짐.

**확정된 운영 SLO 기준**: t3a.small 2대 + nginx 구성에서 안정 처리 상한 VU=800 (≈1,447 RPS), Knee Point는 VU=1,000~1,200.

> 💭 **느낀점**
> - 측정 도구도 측정 대상이다. 시나리오의 sleep·polling 횟수·재시도 정책 하나하나가 서버 부하 패턴을 결정
> - Rate Limiter가 retry storm을 만든 2회차 경험 — 방어 메커니즘이 클라이언트 동작과 결합해 의도치 않은 양상을 만든다
> - 변곡점을 찾았다는 결과보다 두 독립 신호(k6 EOF + Grafana RPS 평탄화)가 같은 구간을 가리켜야 신뢰할 수 있다는 검증 감각이 더 가치 있었음

---

### B-3. nginx 튜닝이 답일 줄 알았다 — 가설이 뒤집힌 페일오버 ablation

시나리오: VU=800 정상 부하 중 T+150s에 app 서버 1을 `docker kill`, T+180s `docker start`. 30초 다운 윈도우에서 사용자가 보는 에러율 측정.

#### baseline — 20% 에러는 구조적 결과였다

| 회차 | http_req_failed |
|------|-----------------|
| 1 | 20.74% |
| 2 | 20.80% |

두 회차 차이 0.06%p. 측정 노이즈가 아닌 구조적 결과. **passive health check는 진짜 요청이 실패해야 격리가 시작되는 사후 대응 메커니즘**이라, 격리 전까지 들어온 요청은 반드시 죽은 서버로 향한다. 수 %~수십 % 에러는 passive HC의 구조적 하한.

#### 첫 가설 — nginx를 더 공격적으로 튜닝하면 에러가 줄 것이다

`nginx.conf` 변경:

```nginx
upstream ticketing_app {
    least_conn;
    # max_fails=1 fail_timeout=5s — 빠른 격리(2회 실패→1회, 10초→5초)로 페일오버 시 사용자 에러 노출 단축
    # 트레이드오프: 정상 부하의 일시적 응답 지연·GC pause를 false-positive로 격리할 위험
    server 172.31.46.152:8080 max_fails=1 fail_timeout=5s;
    server 172.31.37.7:8080 max_fails=1 fail_timeout=5s;
}

location / {
    proxy_pass http://ticketing_app;
    # connect 1s — 죽은 서버 connection refused/SYN drop을 빠르게 감지해 다음 서버로 재시도
    proxy_connect_timeout 1s;
    # 서버 장애·재시작 시 자동으로 다른 서버에 재시도
    proxy_next_upstream error timeout http_502 http_503 http_504;
    proxy_next_upstream_tries 2;
}
```

| 변수 | Before | After |
|------|--------|-------|
| `max_fails` | 2 | 1 |
| `fail_timeout` | 10s | 5s |
| `proxy_connect_timeout` | 5s | 1s |

#### 실측 — 가설이 뒤집혔다

| 지표 | baseline (A) | nginx 튜닝 단독 (B) |
|------|--------------|---------------------|
| http_req_failed | 20.77% | **24.51%** ⚠️ |
| 진입 성공률 | (미측정) | **5%** |

에러율이 오히려 ~4%p 증가, 진입 성공률 5%로 폭락.

**원인**: `max_fails=1` + `connect_timeout=1s`가 VU=800 정상 부하에서 **false-positive 격리** 트리거. 정상 서버라도 부하가 몰리면 1초 안에 connect 응답을 못 주는 순간이 있고, nginx는 그걸 "죽었다"로 판정. 트래픽이 다른 서버로 몰려 그 서버도 같은 일이 벌어짐 → **cascading isolation**.

#### retry 결합 — ablation으로 진짜 주역 식별

같은 튜닝에 클라이언트 retry(최초 시도 + 재시도 2회, 총 3번 시도)를 결합. 핵심 코드:

```javascript
// queue-flow-with-retry.js
const MAX_RETRIES = parseInt(__ENV.K6_RETRY_MAX || '2', 10);
const BACKOFF_BASE_MS = parseInt(__ENV.K6_RETRY_BASE_MS || '100', 10);

// 5xx와 connection error만 재시도 (4xx는 재시도해봐야 또 막힘)
function isRetryable(status) {
  return status === 0 || (status >= 500 && status < 600);
}

function callWithRetry(method, url, body, params) {
  let attempt = 0;
  let res;
  while (attempt <= MAX_RETRIES) {
    res = method === 'POST' ? http.post(url, body, params) : http.get(url, params);
    if (!isRetryable(res.status)) return res;
    if (attempt < MAX_RETRIES) {
      const waitMs = BACKOFF_BASE_MS * Math.pow(2, attempt);  // 100ms, 200ms
      sleep(waitMs / 1000);
    }
    attempt += 1;
  }
  return res;
}
```

#### Ablation 매트릭스

| 조건 | nginx 튜닝 | retry | http_req_failed | 진입 성공률 |
|------|------------|-------|-----------------|-------------|
| A. baseline | OFF | OFF | 20.77% | — |
| B. 튜닝 단독 | ON | OFF | 24.51% ⚠️ | 5% |
| C. 결합 | ON | ON | **11.05%** ✅ | 38% |

C가 baseline보다 좋아진(-47%) 결과만 봤다면 둘 다 효과적이었다고 결론낼 뻔. 하지만 **B가 baseline보다 나쁘다**는 사실이 같이 있어 해석이 달라진다.

- nginx 튜닝 자체는 부작용 (A→B: +3.74%p)
- retry가 그 부작용을 상쇄하고 추가로 개선 (B→C: -13.46%p)
- **결합 효과의 주역은 retry, nginx 튜닝은 보조 역할**

#### 운영 SLO 결론

오픈소스 nginx + passive HC 단독으로는 30초 다운 시 사용자 에러 ~20%가 구조적 하한. **5% 미만 SLO를 잡으려면 active health check (K8s, 클라우드 LB, nginx-plus 중 하나) 도입 필수.**

> 💭 **느낀점**
> - 그럴듯한 가설과 맞는 가설의 거리. "nginx 공격적 튜닝 → 격리 빠름 → 에러 감소"는 누구나 끄덕일 가설이지만 측정 전까지는 정답이 아님
> - ablation 없이 결합 효과만 봤으면 잘못된 운영 가이드 작성. 변경 둘을 같이 적용 vs 각각 분리해서 측정은 도출되는 결론이 다름
> - Prometheus 타겟 누락 — 이후 체크리스트에 "타겟이 모든 인스턴스 잡고 있는가" 항목 추가

---

# 🎯 두 영역을 관통하는 메시지

> 💭 분산 시스템에서 직관은 자주 빗나간다. 두 번의 가설 기각(B-1), 측정 도구가 측정 대상이 되어버린 시행착오(B-2), ablation으로 뒤집힌 결론(B-3), 그리고 한 번이 아니라 20번이 필요한 증명(A). 이 프로젝트의 핵심은 기능을 동작시키는 것이 아니라 시스템의 행동을 데이터로 설명할 수 있게 만드는 것이었다. 직접 측정하고, 측정 결과를 의심하고, 한계까지 기록하는 사이클을 4개월 동안 반복했다.
>
> 그리고 이 과정 전체를 AI와 협업해 진행했다. AI가 만든 코드를 측정으로 검증한 뒤 본인 이름으로 채택하는 방식 — SI 출신이 IT 서비스업으로 이동하며 AI 시대에 적응한 방식 그 자체를 포트폴리오 산출물로 만든 것이 이 프로젝트의 또 다른 결과물이다.
