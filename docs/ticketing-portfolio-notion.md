# 📌 왜 이 프로젝트를 만들었나

설 연휴 귀향길에 KTX를 예매하다가 문득 의문이 들었다.
**"수만 명이 같은 시각에 예매 버튼을 누르는데, 어떻게 한 좌석을 두 명에게 팔지 않을까?"**

이 질문이 출발점이었다. 콘서트 예매를 주제로 정한 건 같은 문제를 더 압축된 형태로 다룰 수 있기 때문이다. 오픈 직후 1분 안에 평소의 수십 배 트래픽이 쏠리고, 그 안에서 같은 좌석을 두고 수백 명이 경쟁한다.

이 프로젝트는 그 안에 들어 있는 **두 개의 다른 문제**를 분리해서 풀었다.

- **트래픽 폭주** — 처리량·지연 문제. k6 부하 테스트로 정량 측정해 SLO 등급 정의
- **좌석 동시 선점** — 정확성·일관성 문제. 20회 독립 시행으로 통계적 증명

> 🛠️ **AI 협업 방식**
> Claude · Cursor를 활용한 바이브 코딩 방식으로 진행. 보일러플레이트와 문서 초안은 AI에 맡기고, **측정 실행 · 가설 기각 · 트레이드오프 판단 · SLO 결정**은 직접 수행했다. AI가 만든 코드와 문서를 그대로 두지 않고 직접 잡아낸 오류 사례는 아래 'AI 협업에서 내가 잡은 것' 섹션에 정리.

---

# 🧩 시스템 아키텍처

-- 시스템 아키텍처 사진

-- CI/CD 파이프라인 사진

| 구분 | 인스턴스 | 사양 |
|------|---------|------|
| Infra (nginx · Redis · Kafka · Prometheus · Grafana) | t3a.medium | 2 vCPU / 4GB |
| App Server 1 | t3a.small | 2 vCPU / 2GB |
| App Server 2 | t3a.small | 2 vCPU / 2GB |
| k6 (부하 테스트) | t3a.small | 2 vCPU / 2GB |
| MySQL (RDS) | db.t4g.micro | 2 vCPU / 1GB |

---

# 🚀 한눈에 — 무엇을 증명했는가

**핵심 설계 3가지**
1. **Redis 분산 락 (SETNX + UUID 토큰 + Lua)** — 좌석 단위 잠금, Redisson 미사용
2. **잔여석 캐시 (`@Cacheable` TTL=2s + 6곳 evict)** — DB 폴링 병목 해소
3. **앱 2대 + nginx `least_conn`** — 분산 환경에서도 락 무결성 유지

**증명한 수치**

| 항목 | 결과 |
|------|------|
| 좌석 동시 선점 정확성 | VU=100, **20회 독립 시행 모두 정확히 1건** 성공 |
| 안정 운영 상한 | VU=800 (≈ **1,447 RPS**), p95 **164ms**, 에러 **0%** |
| 캐시 도입 효과 | p95 2.06s → **444ms (▼78%)**, RPS 376 → **834/s (▲122%)** |
| Knee Point | VU **1,000~1,200** (k6 EOF + Grafana RPS 평탄화 동시점) |
| 페일오버 ablation | nginx 튜닝 단독 **+3.74%p 악화**, retry 결합 -47% — 진짜 주역은 retry |

**숨기지 않고 적은 한계** — VT 단독 기여도 미분리 · 단일 Redis 전제 · 잔여석 캐시는 CB 적용 범위 밖 · 클라이언트 retry는 k6에서만 측정.

---

# 🤝 AI 협업 — 위임한 것, 직접 한 것, 내가 잡은 것

## 위임한 영역 (AI가 빠르게 잘하는 일)

- 보일러플레이트: DTO · Controller · Repository 스캐폴드, Bean Validation
- Lua 스크립트 **초안**, 문서 1차 표현, 가설 후보 발산 ("pool 늘려보자", "VT 켜보자")
- 엣지 케이스 후보 나열

## 직접 한 영역 (AI에 맡기면 안 되는 일)

- **아키텍처 결정** — Redisson 미도입(단일 Redis 전제), nginx 단독(ALB 미사용), Saga REQUIRES_NEW
- **임계치 산정** — 락 TTL 3초(정상 1초 + 3배 마진), 캐시 TTL 2초, pool 30
- **부하 테스트 설계·실행** — k6 시나리오 4종, 4회차 시행착오 디버깅
- **가설 기각** — pool↑·VT 두 가설 기각 후 진짜 병목(폴링 빈도) 도출
- **재현성 검증** — 핵심 측정 최소 2회, 정확성은 20회 반복

## 내가 직접 잡은 AI의 오류

AI가 생성한 코드와 문서를 그대로 두지 않고 측정·코드 grep으로 검증한 결과 발견한 사례들. 이의를 제기하지 않았다면 그대로 배포되거나 발표 자료에 실릴 뻔한 것들이다.

### 1. Lua 스크립트 원자성 — "동작하긴 하는데 왜 안전한가" 재검토

AI가 처음 작성한 Lua 초안은 SETNX·EXISTS·ZADD를 그냥 순서대로 나열한 형태였다. 동작은 했지만 **"이게 왜 원자적인가"** 라는 답이 명확하지 않았다.

직접 Redis 문서를 다시 읽고, Lua 실행이 단일 명령으로 큐잉된다는 사실에 근거해 "이 스크립트가 보장하는 것은 좌석→토큰, 토큰→홀드, 만료 ZSET 등록이 **중간 개입 없이 단일 트랜잭션으로 완료된다**는 것"이라는 명확한 근거를 정리했다. 그제야 unlock 스크립트에서 GET/DEL을 한 번에 묶어야 하는 이유도 같은 논리에서 도출됐다.

> AI가 만든 게 "돌아간다"와 "왜 안전한지 설명 가능하다"는 다르다.

### 2. HoldController 응답 코드 버그 — 200 OK인데 201 행세

부하 테스트 중 k6 `201 체크`가 0건으로 잡혔는데 `http_req_failed` 역산하니 1건은 비실패였다. AI가 생성한 컨트롤러 코드를 직접 추적해보니 `ResponseEntity` 없이 `HoldResponse`만 반환해 Spring이 기본 200 OK를 내고 있었다.

```java
@PostMapping
@ResponseStatus(HttpStatus.CREATED)  // 부하 테스트 중 발견·직접 추가
public HoldResponse createHold(...) { ... }
```

응답 코드 분포까지 직접 세지 않았으면 영영 못 잡았을 결함.

### 3. nginx 튜닝 가설 — "그럴듯한 답"에 속지 않기

AI가 페일오버 개선책으로 제시한 첫 답은 "`max_fails=1`, `connect_timeout=1s`로 더 공격적으로 격리하라"였다. 직관적으로 맞아 보였고 실제로 nginx 설정도 그렇게 바꿨다.

그런데 실측해보니 **에러율이 +3.74%p 오히려 악화**됐다. AI 답을 그대로 운영 가이드에 적었다면 잘못된 결론이 됐을 것이다.

직접 변수 분리 매트릭스(baseline / nginx 단독 / retry 결합)를 짜서 측정한 결과, **진짜 개선 주역은 retry이고 nginx 튜닝은 보조**라는 정반대 결론을 얻었다. 자세한 측정은 아래 부하 테스트 dropdown 참조.

### 4. 노션 본문 정직성 검수 — 5건 패치

AI가 만든 노션 본문 초안에 측정 데이터와 어긋나는 표현 5건이 있었다. 직접 코드 grep과 k6 로그 대조로 잡아낸 사례:

- "캐시 적용 후 VT off 비교도 측정" → 실제로는 VT on만 측정. **"VT 단독 기여도 분리 미완성"으로 정정**
- "Resilience4j가 모든 Redis 호출을 보호" → 실제로 `@Cacheable`은 CB 적용 범위 밖. **"잔여석 캐시는 CB 적용 범위 밖"으로 한계 명시**
- "클라이언트 retry로 실서비스 에러율 11%" → retry는 k6 시나리오에서만 측정. **"실 프론트엔드 미구현"으로 정정**
- 기타 2건 (수치 반올림 차이, evict 호출 지점 개수)

> AI는 빠르고, 동시에 **틀려도 자신감 있게 틀린다.** 검증 책임은 결국 본인 몫.

---

# 🎚 핵심 트레이드오프

각 결정에서 **무엇을 거부하고 무엇을 받아들였는가**. 자세한 측정 근거는 부하 테스트 dropdown에서 확인 가능.

| 영역 | 선택 | 거부한 대안 | 근거 | 인정한 한계 |
|------|------|------------|------|------------|
| 분산 락 | SETNX + UUID + Lua | Redisson(Redlock) | 단일 Redis 환경에서 분산 합의는 무의미. 토큰 + Lua로 등가 안전성 | Sentinel/Cluster 전환 시 재검토 |
| 로드밸런서 | nginx 단독 (passive HC) | ALB / nginx-plus active HC | 비용·운영 단순성 | 30초 다운 시 사용자 에러 ~20% 구조적 하한 |
| 잔여석 동시성 | 캐시 TTL=2s + 6곳 evict | 강한 일관성 (실시간 COUNT) | 잔여석은 1~2초 근사 허용. p95 78%↓ 측정 | evict 누락 시 stale 위험 (6곳 grep 일치 확인) |
| 스레드 모델 | Virtual Thread (Java 21) | 플랫폼 스레드 + 풀 확대 | IO-bound 폴링에 적합 (JVM 225→30 실측) | VT 단독 기여도 분리 미완성 |
| 락 TTL | 3초 (외부화) | 1초 / 10초 | 정상 흐름 1초 측정 + 3배 마진 | 비정상 종료 시 3초 자원 고립 |
| 운영 지표 | P95 + 에러율 분리 SLO | 단일 P99 | 등급별 요구가 다름. P99는 GC·지터에 흔들려 단일 실험 노이즈가 큼 | P99·Max는 보조 추적만 |

---

# 📊 부하 테스트 (Dropdown — 펼쳐서 보기)

<details>
<summary><b>🧰 측정 인프라 구축 — 본 측정 전에 도구부터 고쳐야 했다</b></summary>

본 측정 사례 전에 **측정 인프라 자체를 구축하는 과정**부터. 결과만 보면 "어떻게 측정했나"가 빠진다.

### 1. Grafana p95가 평탄선 — Prometheus 히스토그램 누락

`histogram_quantile()`이 요구하는 `*_bucket` 시리즈가 Prometheus에 없는 게 원인. Spring Boot Actuator는 percentile만 발행하고 bucket은 옵트인.

```properties
management.metrics.distribution.percentiles-histogram.http.server.requests=true
```

### 2. Kafka 헬스 인디케이터가 60초 타임아웃

`/actuator/health` 호출이 부하 중 60초마다 멈춤. Spring Boot 기본 Kafka indicator가 broker `metadata` 호출을 동기로 수행해 헬스 체크를 막음.

```properties
management.health.kafka.enabled=false
```

### 3. Prometheus가 app2를 모르고 있었다

페일오버 baseline 측정 도중 server1을 kill하자 Grafana 패널이 공백. 그런데 k6 합산 RPS는 끊기지 않음. 두 신호 불일치가 단서였다. `prometheus.yml`에 server1 IP만 등록돼 있었다.

> **측정 인프라가 측정 대상의 가용성에 의존하면 가장 필요한 순간에 데이터가 사라진다.**

### 4. 비즈니스 메트릭 커스텀 등록

서버 헬스만으로는 도메인 행동 추적 불가. 홀드 성공·락 경합·Lua 중복 검출을 Micrometer 카운터로 분리 등록.

```java
this.holdCreatedCounter = Counter.builder("ticketing_hold_created_total").register(meterRegistry);
this.lockFailureCounter = Counter.builder("ticketing_lock_acquire_failures_total").register(meterRegistry);
this.holdConflictCounter = Counter.builder("ticketing_hold_conflict_total").register(meterRegistry);
```

</details>

<details>
<summary><b>🔒 Part A. 분산 락 정확성 — 20회 독립 시행 통계 증명</b></summary>

### 문제 정의

- **환경**: App 2대 + nginx 로드밸런서
- **과제**: 동일 좌석에 동시 선점 요청이 수렴할 때 정확히 1개만 성공
- **검증 방향**: 코드 레벨 낙관적 예측 배제, 부하 테스트로 통계적 증거 확보

### 설계 — SETNX + UUID 토큰 + Lua 원자 해제

```java
// RedisLockService.java
public Optional<String> tryLock(String key, Duration ttl) {
    String token = UUID.randomUUID().toString();
    Boolean success = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
    return Boolean.TRUE.equals(success) ? Optional.of(token) : Optional.empty();
}

// 토큰 일치할 때만 해제. GET/DEL을 Lua로 묶어 다른 쓰레드가 새로 잡은 락을 실수로 지우는 사고 차단
private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
    Long.class
);
```

### 검증 시나리오

| 항목 | 값 |
|------|------|
| 도구 | k6 (shared-iterations) |
| VU | 100 / iteration 100 |
| 대상 | 단일 좌석 |
| 구성 | 1대 직접 / 2대 nginx 분산 |
| 반복 | 각 구성 10회, 총 **20회 독립 시행** |
| 회차 간 초기화 | `FLUSHDB` + 좌석 status 복원 |

### 결과

| 응답 | 1대 직접 (10회) | 2대 nginx (10회) |
|------|----------------|-------------------|
| 201 선점 성공 | **모든 회차 정확히 1건** | **모든 회차 정확히 1건** |
| 5xx | 0건 | 0건 |
| 409 이미 선점 | 0~7건 | 0~21건 |
| 429 락 경합 | 92~99건 | 78~99건 |

20회 전부 201 = 1건. nginx로 2대에 분산되어도 단일 Redis 락의 원자성이 유지됨이 통계적으로 확인.

### 인정한 한계

본 검증은 **단일 Redis 환경의 통계적 증거**일 뿐. Sentinel/Cluster 전환 시 Redlock 필요 시점이 오고 라이브러리 선택을 재검토해야 함.

</details>

<details>
<summary><b>⭐ Part B-1. p95가 안 떨어진다 — 두 가설 모두 빗나간 이야기</b></summary>

초기 측정: VU=800 큐 폴링 → **p95 = 1.93s**. "고장났다" 수준.

Grafana 신호: `hikaricp_connections_active` 10에 평탄 (풀 한계), `pending` 170까지 단조 증가.

### 첫 가설 — Hikari pool 10 → 30

| 지표 | pool=10 | pool=30 | 변화 |
|------|---------|---------|------|
| p95 | 1.93s | 1.85s | ▼ 80ms (-4.1%) |
| RPS | ~408/s | ~386/s | ▼ 22/s |
| DB pending | 0→170 단조 적체 | 적체 패턴 유지 | **해소 안 됨** |

풀을 3배로 늘렸는데 차이 없음. DB pending 적체가 그대로.

### 두 번째 가설 — Virtual Thread

```
JVM threads: 225 → 30   ▼ 87%
p95:         1.85s → 2.06s  ▲ 악화
```

두 가설 모두 빗나감.

### 두 그래프 불일치가 단서

**pending은 줄었는데 p95는 안 줄었다.** 두 그래프가 같이 안 움직임 → 커넥션 자체는 병목이 아니다.

폴링 구조 재분석: `GET /api/queue/status` 1회마다 DB 쿼리 3회. 풀을 늘리면 더 많은 폴링이 풀 슬롯을 점유 → 그대로 DB로 흘러감. **진짜 병목은 DB 쿼리 빈도.**

### 해결 — 잔여석 캐시 (TTL=2s + 6곳 evict)

도메인 판단: 잔여석은 1~2초 근사 허용 가능. `@Cacheable` 적용 + 6개 시점 evict.

```java
@Cacheable(cacheNames = CacheNames.QUEUE_STATUS_AVAILABLE_SEATS, key = "#concertId")
public long countAvailableSeatsForQueueStatus(Long concertId) { ... }
```

evict 호출 6개 지점 (코드 grep 확인):
- `HoldService:146` 홀드 생성 / `:166` 홀드 취소
- `HoldCleanupScheduler:107` 만료 정리
- `ReservationConfirmedEventListener:41` 예약 확정
- `ReservationService:208` 예약 환불
- `SellerService:188` 좌석 추가

### 결과

| 지표 | 캐시 전 | 캐시 후 | 변화 |
|------|---------|---------|------|
| p95 | 2.06s | **444ms** | **▼ 78%** |
| RPS | ~376/s | **~834/s** | **▲ 122%** |
| DB pending | 170 단조 적체 | **거의 0** | 적체 해소 |

**변경 1개로 p95 78% 감소, RPS 2.2배.** 가장 임팩트가 큰 단일 변경.

### 인정한 한계

캐시 후 환경은 `pool=30 + VT on` 한 조건만 측정. 같은 환경에서 VT off 비교 미진행 → **2.2배 RPS 안에서 VT 단독 기여도 분리 불가**.

</details>

<details>
<summary><b>⭐ Part B-2. Knee Point 탐색 — 시나리오 자체가 부하 생성기였다</b></summary>

목표: VU=800 에러 0%, VU=1500 에러 3.41%. 사이 변곡점을 찾아 SLO 기준 확보.

### 4회차 시행착오

| 회차 | 문제 | 원인 | 조치 |
|------|------|------|------|
| 1 | ramp 안 됨 | 기존 stress profile이 step 정의 무시 | `knee-point.js` 신규 |
| 2 | 진입 성공률 21% | `sleep(1)` 후 재시도 → 1000명 동시 재시도 → Rate Limiter가 공격 인식 → 429 retry storm | `sleep(5)` + 백오프 |
| 3 | 5xx 30% | `MAX_POLLS=1000`(4분) → VU 600+ 누적 폴링 | `MAX_POLLS=300`으로 축소 |
| 4 | 측정 성공 | — | — |

### Knee Point 식별 — 두 독립 신호 일치

1. **k6**: `WARN[0178] EOF` — 178초부터 연결 끊김
2. **Grafana**: 같은 178초부터 RPS 평탄화

178초 = VU 1000→1200 전환 시점.

**SLO 확정**: 안정 처리 상한 VU=800 (≈1,447 RPS), Knee Point VU=1,000~1,200.

> "변곡점을 찾았다"보다 **"두 독립 신호가 같은 시점을 가리켰다"**는 검증 감각이 더 값졌다.

</details>

<details>
<summary><b>⭐ Part B-3. nginx 튜닝이 답일 줄 알았다 — 변수 분리로 뒤집힌 가설</b></summary>

시나리오: VU=800 정상 부하 중 T+150s에 app1 `docker kill`, T+180s `docker start`. 30초 다운 윈도우 에러율 측정.

### baseline — 20% 에러는 구조적

| 회차 | http_req_failed |
|------|-----------------|
| 1 | 20.74% |
| 2 | 20.80% |

차이 0.06%p. **passive HC는 진짜 요청이 실패해야 격리가 시작되는 사후 메커니즘** → 격리 전까지 들어온 요청은 죽은 서버로 향함. ~20%는 구조적 하한.

### 첫 가설 — nginx 공격적 튜닝하면 에러 감소?

| 변수 | Before | After |
|------|--------|-------|
| `max_fails` | 2 | 1 |
| `fail_timeout` | 10s | 5s |
| `proxy_connect_timeout` | 5s | 1s |

### 실측 — 가설 뒤집힘

| 지표 | baseline (A) | nginx 튜닝 단독 (B) |
|------|-------------|---------------------|
| http_req_failed | 20.77% | **24.51%** ⚠️ |
| 진입 성공률 | — | **5%** |

**+3.74%p 악화**. 원인: `max_fails=1 + connect_timeout=1s`가 정상 부하에서 **false-positive 격리** 트리거 → 트래픽 다른 서버로 쏠림 → **연쇄 격리**.

### 변수 분리 매트릭스 — 진짜 주역 찾기

같은 튜닝에 클라이언트 retry(최초 + 2회 재시도, 지수 백오프) 결합.

| 조건 | nginx 튜닝 | retry | http_req_failed | 진입 성공률 |
|------|-----------|-------|-----------------|------------|
| A. baseline | OFF | OFF | 20.77% | — |
| B. 튜닝 단독 | ON | OFF | 24.51% ⚠️ | 5% |
| C. 결합 | ON | ON | **11.05%** ✅ | 38% |

C만 봤다면 둘 다 효과적이라고 결론낼 뻔. **B가 baseline보다 나쁘다**는 사실이 같이 있어 해석이 달라짐:

- nginx 튜닝 자체는 부작용 (A→B: +3.74%p)
- retry가 부작용을 상쇄하고 추가 개선 (B→C: -13.46%p)
- **결합 효과의 주역은 retry, nginx 튜닝은 보조**

### SLO 결론

오픈소스 nginx + passive HC 단독으로는 30초 다운 시 사용자 에러 ~20%가 구조적 하한. 클라이언트 retry로 11%까지 흡수 가능. **5% 미만 SLO 목표를 잡으려면 active HC(K8s · 클라우드 LB · nginx-plus) 도입 필수.**

</details>

---

# 🎯 마지막 느낀점

4개월 동안 가장 자주 한 생각은 **"내가 만든 시스템을 내가 잘 모른다"** 였다. 코드가 돌아간다는 게 시스템이 잘 돌아간다는 뜻이 아니었고, 부하 테스트가 한 번 잘 찍혔다고 SLO를 잡을 수 있는 것도 아니었다.

**가설을 의심하는 감각이 가장 비쌌다.** B-1에서 pool 증설과 VT를 연달아 기각당한 게 컸다. 둘 다 너무 직관적으로 맞아 보였는데 두 그래프가 같이 안 움직이는 신호를 처음엔 그냥 지나쳤다. 데이터를 의심하기보다 내 가설을 믿고 싶은 마음이 더 셌다. B-3 페일오버에서도 같은 일이 반복됐다. "nginx 공격적으로 튜닝하면 에러 감소"는 너무 그럴듯해서 측정 없이 결론낼 뻔했다. 변수를 분리하니 정반대였다.

**AI와 같이 일하는 방식도 계속 다듬었다.** AI는 빠르고, 동시에 **틀려도 자신감 있게 틀린다.** Lua 원자성 근거를 다시 정리하고, 200 OK 버그를 잡고, nginx 튜닝이 부작용임을 측정으로 뒤집고, 노션 본문 정직성 5건을 패치한 경험이 모두 같은 결론을 가리켰다. **검증 책임은 결국 엔지니어 몫**이라는 것.

**한계를 한계로 적는 일이 의외로 가장 어려웠다.** VT 단독 기여도 미분리, 단일 Redis 전제, 잔여석 캐시 CB 적용 범위 밖, 클라이언트 retry는 k6에서만 측정 — 본문에서 빼버리고 싶었던 적이 한 번씩 다 있었다. 숨기는 쪽이 발표하기엔 매끄럽기 때문이다. 그래도 적어둔 건, 면접에서 코드를 열어보면 결국 드러날 부분이라 먼저 인정하는 쪽이 낫다는 단순한 판단이었다.

엔지니어링은 내 생각이 맞음을 증명하는 과정이 아니라, **지표라는 수치를 통해 시스템의 본질을 찾아가는 과정**이라는 걸 배운 시간이었다.
