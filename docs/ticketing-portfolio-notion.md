# 프로젝트 동기

설 연휴에 KTX를 예매하다가 한 가지 의문이 생겼다.
**"수만 명이 같은 시각에 예매 버튼을 누르는데, 어떻게 한 좌석을 두 명에게 팔지 않을까?"**

이 질문에서 시작한 프로젝트다. 콘서트 예매를 주제로 정한 이유는 같은 문제를 더 짧은 시간 안에 압축해서 다룰 수 있기 때문이다. 오픈 직후 1분 안에 평소의 수십 배 트래픽이 쏠리고, 그 안에서 같은 좌석을 두고 수백 명이 경쟁한다.

이 안에는 닮아 보이지만 측정 방식이 다른 두 문제가 동시에 들어 있다.

- **트래픽 폭주** — 처리량과 지연 문제. k6 부하 테스트로 정량 측정해 SLO 등급을 정의했다.
- **좌석 동시 선점** — 정확성과 일관성 문제. 단일 좌석 동시 선점 시나리오를 20회 독립 시행해 통계적으로 검증했다.

> 작업 방식에 대한 노트
> Claude·Cursor를 함께 사용해 진행한 프로젝트다. 보일러플레이트와 문서 초안은 AI에 위임했고, 측정 실행·가설 검증·트레이드오프 판단·SLO 결정은 직접 수행했다. 분담 기준과 AI 산출물에서 발견·수정한 오류는 본문 'AI 협업' 섹션에 정리했다.

---

# 시스템 아키텍처

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

# 요약 — 무엇을 검증했는가

핵심 설계는 세 가지다.

1. Redis 분산 락 (SETNX + UUID 토큰 + Lua) — 좌석 단위 잠금. Redisson은 사용하지 않았다.
2. 잔여석 캐시 (`@Cacheable` TTL=2s + 6곳 evict) — 큐 폴링이 유발하는 DB 부하를 줄이는 용도다.
3. 앱 2대 + nginx `least_conn` — 분산 환경에서 락 정확성이 유지되는지 확인했다.

검증한 지표는 다음과 같다.

| 항목 | 결과 |
|------|------|
| 좌석 동시 선점 정확성 | VU=100, 20회 독립 시행 모두 정확히 1건 성공 |
| 안정 운영 상한 | VU=800 (≈ 1,447 RPS), p95 164ms, 에러 0% |
| 캐시 도입 효과 | p95 2.06s → 444ms (▼78%), RPS 376 → 834/s (▲122%) |
| Knee Point | VU 1,000~1,200 (k6 EOF 발생 시점과 Grafana RPS 평탄화 시점 일치) |
| 페일오버 ablation | nginx 튜닝 단독 +3.74%p 악화, retry 결합 -47% |

명시한 한계는 다음과 같다. Virtual Thread 단독 기여도는 분리 측정하지 못했고, 락 안전성은 단일 Redis 환경에 한정된 결론이다. 잔여석 캐시는 Resilience4j Circuit Breaker 적용 범위 밖이며, 클라이언트 retry는 k6 시나리오에서만 측정했다.

---

# AI 협업

## 분담 기준

작업을 시작하기 전에 다음 기준을 정해두고 진행했다.

- **정답이 외부에 존재하는 일은 AI에 위임한다.** 스캐폴드 코드, 문법, 라이브러리 사용법, 표준 설계 패턴이 여기에 해당한다.
- **정답이 이 프로젝트의 제약·측정·도메인 안에만 있는 일은 직접 수행한다.** 임계치 산정, 트레이드오프 결정, 측정 시나리오 설계, 가설 검증이 여기에 해당한다.

전자는 검증 비용이 낮다. 컴파일·테스트·문서로 확인 가능하기 때문이다. 후자는 측정 데이터와 도메인 이해가 없으면 정답에 닿을 수 없다. AI가 후자에 답을 내놓을 때는 일반론에 가까웠고, 실제로 그중 한 답이 측정으로 뒤집힌 사례가 있다(아래 'AI 산출물에서 발견·수정한 사례' 3번).

## 위임한 영역

- DTO·Controller·Repository 스캐폴드, Bean Validation 어노테이션
- Lua 스크립트와 SQL의 1차 초안
- 문서·주석의 1차 표현
- 시도해볼 가설 후보 발산 ("pool을 늘려본다", "Virtual Thread를 적용해본다" 등)
- 놓친 엣지 케이스 후보 나열

## 직접 수행한 영역

- 아키텍처 결정 — Redisson 미도입(단일 Redis 전제), nginx 단독(ALB 미사용), Saga + REQUIRES_NEW 적용
- 임계치 산정 — 락 TTL 3초(정상 흐름 1초 측정 + 3배 마진), 캐시 TTL 2초, Hikari pool 30
- 부하 테스트 설계와 실행 — k6 시나리오 4종 작성, 4회차 시행착오 디버깅
- 가설 검증과 기각 — pool 증설·Virtual Thread 두 가설을 기각하고 실제 병목(폴링 빈도)을 도출
- 재현성 확보 — 핵심 측정은 최소 2회 반복, 정확성 검증은 20회 반복

## AI 산출물에서 발견·수정한 사례

AI가 생성한 코드와 문서를 그대로 두지 않고 측정·코드 grep으로 확인한 결과 발견한 사례다.

### 1. Lua 스크립트의 원자성 근거 재정리

AI가 작성한 Lua 초안은 동작했지만, **"왜 원자적인가"** 라는 질문에 대한 근거가 코드 안에 명시되어 있지 않았다. Redis 공식 문서를 다시 확인해 Lua 실행이 단일 명령으로 큐잉된다는 사실에 근거를 두고, 좌석→토큰·토큰→홀드·만료 ZSET 등록 세 동작이 중간 개입 없이 끝나야 하는 이유를 주석에 정리했다. unlock 스크립트의 GET/DEL을 한 줄로 묶은 이유도 같은 논리에서 도출했다.

### 2. HoldController 응답 코드 누락

부하 테스트 중 k6의 `201` 체크가 0건으로 잡혔는데, `http_req_failed` 역산 결과 비실패 응답이 존재했다. 컨트롤러를 확인하니 `ResponseEntity` 없이 `HoldResponse`만 반환하고 있었고, Spring이 기본 200 OK를 내고 있었다.

```java
@PostMapping
@ResponseStatus(HttpStatus.CREATED)  // 부하 테스트 중 발견 후 추가
public HoldResponse createHold(...) { ... }
```

응답 코드 분포까지 따로 집계하지 않았다면 발견하기 어려운 결함이었다.

### 3. nginx 튜닝 가설의 측정 결과

AI가 페일오버 개선책으로 제시한 답은 `max_fails=1`, `proxy_connect_timeout=1s`로 격리 임계를 공격적으로 낮추라는 것이었다. 직관과는 일치하는 방향이었기 때문에 그대로 적용해 측정했다.

실측 결과는 에러율이 오히려 +3.74%p 증가했다. 정상 부하 상황에서 false-positive 격리가 발생했고, 연쇄 격리로 이어졌기 때문이다. AI 답을 그대로 운영 가이드로 옮겼다면 잘못된 결론이 됐을 사례다. 직접 변수 분리 매트릭스(baseline / nginx 단독 / retry 결합)를 설계해 측정한 뒤, **실제 개선 효과는 클라이언트 retry에서 나오고 nginx 튜닝은 보조 역할**이라는 정반대 결론을 정리했다. 자세한 측정은 부하 테스트 dropdown의 Part B-3 참조.

### 4. 문서 초안에서 측정 데이터와 어긋난 표현 다섯 건 수정

AI가 작성한 문서 초안에는 측정 데이터와 어긋난 표현이 포함되어 있었다. 코드 grep과 k6 로그 대조로 확인한 항목은 다음과 같다.

- "캐시 적용 후 Virtual Thread off 비교도 측정했다" → 실제로는 VT on 환경만 측정했다. 본문은 "VT 단독 기여도 분리 미완성"으로 정정했다.
- "Resilience4j가 모든 Redis 호출을 보호한다" → `@Cacheable`은 Spring `RedisCacheManager`의 기본 동작 영역이라 CB 적용 범위 밖이다. "잔여석 캐시는 CB 적용 범위 밖"으로 한계를 명시했다.
- "클라이언트 retry로 실서비스 에러율 11%로 흡수" → retry는 k6 시나리오에서만 동작했다. "실 프론트엔드 미구현"으로 정정했다.
- 수치 반올림 차이 1건, evict 호출 지점 개수 1건 수정.

문서 검수에서 얻은 결론은 단순했다. AI 산출물의 사실 정확성은 산출 도구가 아니라 사용자가 책임진다는 것이다.

---

# 핵심 트레이드오프

## 제약이 결정을 강제했다

본 프로젝트의 의사결정은 진공 상태에서 한 것이 아니라 명확한 제약 안에서 선택한 결과다. 같은 문제라도 제약이 바뀌면 답이 달라진다.

| 제약 조건 | 그래서 따라온 의사결정 |
|----------|----------------------|
| 단일 Redis 노드 (Sentinel/Cluster 아님) | Redlock의 분산 합의가 무의미 → Redisson 미도입, SETNX + 토큰 + Lua |
| t3a.small 2대 (vCPU 2, RAM 2GB) | 풀·스레드를 무한 확장할 수 없음 → 폴링 빈도 자체를 줄이는 캐시 도입 |
| 인프라 비용 한계 (개인 프로젝트) | ALB·nginx-plus·K8s 미사용 → 무료 nginx + passive HC, 5% 미만 SLO는 비목표 |
| 잔여석 도메인 특성 | 1~2초 근사 허용 가능 → 강한 일관성 미선택, TTL=2s 캐시 |
| 단발 부하 시나리오 | 99.X% 가용성 산출 표본 부족 → 부하 등급별 분리 SLO로 대체 |

## 각 결정의 선택과 거부

| 영역 | 선택 | 거부한 대안 | 강제한 제약 | 인정한 한계 |
|------|------|------------|------------|------------|
| 분산 락 | SETNX + UUID + Lua | Redisson (Redlock) | 단일 Redis 노드 환경 | Sentinel/Cluster 전환 시 재검토 필요 |
| 로드밸런서 | nginx 단독 (passive HC) | ALB / nginx-plus active HC | 인프라 비용 한계 | 30초 다운 시 에러율 ~20%가 구조적 하한 |
| 잔여석 동시성 | 캐시 TTL=2s + 6곳 evict | 강한 일관성 (실시간 COUNT) | t3a.small 처리량 + 도메인 허용 범위 | evict 누락 시 stale 위험 (6곳 grep 확인) |
| 스레드 모델 | Virtual Thread (Java 21) | 플랫폼 스레드 + 풀 확대 | RAM 2GB — 풀 무한 확장 불가, IO-bound 워크로드 | VT 단독 기여도 분리 미완성 |
| 락 TTL | 3초 (외부화) | 1초 / 10초 | 정상 흐름 TAT 1초 측정 + 3배 마진 | 비정상 종료 시 3초 자원 고립 |
| 운영 지표 | P95 + 에러율 분리 SLO | 단일 P99 | 단발 부하 시나리오 노이즈 | P99·Max는 보조 추적만 |

---

# 부하 테스트

분량이 길어 dropdown으로 분리했다. 측정 인프라 구축 과정과 Part A·B-1·B-2·B-3 네 사례가 포함되어 있다.

<details>
<summary><b>측정 인프라 구축 — 측정 도구를 먼저 신뢰할 수 있게 만들기</b></summary>

부하 테스트 결과를 보기 전에 측정 인프라 자체를 검증하는 과정이 필요했다. 본 측정 전에 발견한 네 가지 문제다.

### 1. Grafana p95가 평탄선으로 표시되는 문제

`http_req_duration`의 p95를 Grafana에 표시하려 했으나 평탄선만 출력됐다. `histogram_quantile()`이 요구하는 `*_bucket` 시리즈가 Prometheus에 존재하지 않았기 때문이다. Spring Boot Actuator는 기본적으로 percentile 메트릭만 발행하고 bucket 시리즈는 옵트인이다.

```properties
management.metrics.distribution.percentiles-histogram.http.server.requests=true
```

해당 설정을 추가한 뒤에야 `histogram_quantile(0.95, sum by(le)(rate(http_server_requests_seconds_bucket[1m])))` 쿼리가 정상 동작했다.

### 2. Kafka 헬스 인디케이터가 부하 시 60초 타임아웃

`/actuator/health` 호출이 부하 중 약 60초마다 멈추는 현상이 발생했다. Spring Boot 기본 Kafka 헬스 인디케이터가 broker `metadata` 호출을 동기로 수행하는데, 부하 상황에서 응답 지연이 헬스 응답 전체를 막아 nginx 헬스 체크에 영향을 주고 있었다. 비활성화로 해결했다.

```properties
management.health.kafka.enabled=false
```

### 3. Prometheus가 app2를 인지하지 못한 상태

페일오버 baseline 1회차 측정 중 server1을 docker kill했을 때 Grafana 패널이 비었지만 k6 합산 RPS는 정상이었다. 두 신호의 불일치가 단서였다. 확인 결과 `prometheus.yml`의 `scrape_configs`에 server1만 등록되어 있었다. server2를 추가한 뒤 1회차를 폐기하고 재측정했다.

이 경험 이후 부하 테스트 체크리스트에 "타겟이 모든 인스턴스를 포함하는가" 항목을 추가했다.

### 4. 비즈니스 메트릭 커스텀 등록

서버 헬스만으로는 도메인 동작을 추적할 수 없어 핵심 지점에 Micrometer 카운터를 직접 등록했다. 홀드 성공, 락 경합, Lua 중복 검출을 각각 별도 카운터로 분리했다.

```java
this.holdCreatedCounter = Counter.builder("ticketing_hold_created_total").register(meterRegistry);
this.lockFailureCounter = Counter.builder("ticketing_lock_acquire_failures_total").register(meterRegistry);
this.holdConflictCounter = Counter.builder("ticketing_hold_conflict_total").register(meterRegistry);
```

</details>

<details>
<summary><b>Part A. 분산 락 정확성 — 20회 독립 시행 통계 검증</b></summary>

### 문제 정의

- 환경: 애플리케이션 서버 2대 + nginx 로드밸런서
- 과제: 동일 좌석에 동시 선점 요청이 수렴할 때 정확히 1개만 성공해야 한다.
- 검증 방향: 코드 레벨의 낙관적 예측에 의존하지 않고 부하 테스트로 통계적 증거를 확보한다.

### 설계 — SETNX + UUID 토큰 + Lua 원자 해제

```java
// RedisLockService.java
public Optional<String> tryLock(String key, Duration ttl) {
    String token = UUID.randomUUID().toString();
    Boolean success = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
    return Boolean.TRUE.equals(success) ? Optional.of(token) : Optional.empty();
}

// 토큰이 일치할 때만 해제한다. GET/DEL을 Lua로 묶어
// 다른 쓰레드가 새로 획득한 락을 잘못 해제하는 사고를 방지한다.
private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
    Long.class
);
```

### 검증 시나리오

| 항목 | 값 |
|------|------|
| 도구 | k6 (shared-iterations executor) |
| VU | 100 / iteration 100 |
| 대상 | 단일 좌석 |
| 구성 | 1대 직접 / 2대 nginx 분산 |
| 반복 | 각 구성 10회, 총 20회 독립 시행 |
| 회차 간 초기화 | `FLUSHDB` + 좌석 status 복원 + `seat_hold` 삭제 |

### 결과

| 응답 | 1대 직접 (10회) | 2대 nginx (10회) |
|------|----------------|-------------------|
| 201 선점 성공 | 모든 회차 정확히 1건 | 모든 회차 정확히 1건 |
| 5xx | 0건 | 0건 |
| 409 이미 선점 | 0~7건 | 0~21건 |
| 429 락 경합 | 92~99건 | 78~99건 |

20회 시행 전부에서 201 응답이 정확히 1건씩 발생했다. nginx로 요청이 2대에 분산되어도 단일 Redis 락의 원자성이 유지된다는 사실이 통계적으로 확인됐다. 409와 429는 모두 중복 선점을 막는 정상 응답이다.

### 인정한 한계

본 검증은 단일 Redis 환경의 통계적 증거에 한정된다. Sentinel/Cluster 환경으로 전환할 경우 Redlock이 필요한 시점이 발생하며, 그 시점에는 라이브러리 선택을 재검토해야 한다.

</details>

<details>
<summary><b>Part B-1. p95가 떨어지지 않는 문제 — 두 가설 모두 기각</b></summary>

초기 측정 결과 VU=800 큐 폴링 환경에서 p95가 1.93초였다. 사용자 입장에서 응답이 늦다고 느껴지는 수준이다.

Grafana에서 관찰된 신호는 두 가지였다. `hikaricp_connections_active`가 10에 평탄하게 도달했고, `hikaricp_connections_pending`은 170까지 단조 증가했다. 풀이 들어오는 요청을 따라잡지 못한다고 판단했다.

### 첫 번째 가설 — Hikari pool 10 → 30

| 지표 | pool=10 | pool=30 | 변화 |
|------|---------|---------|------|
| p95 | 1.93s | 1.85s | ▼ 80ms (-4.1%) |
| RPS | ~408/s | ~386/s | ▼ 22/s |
| DB pending | 0→170 단조 적체 | 동일 패턴 유지 | 해소되지 않음 |

풀을 3배로 늘렸지만 의미 있는 차이가 관찰되지 않았다. DB pending 적체 패턴도 그대로였다.

### 두 번째 가설 — Virtual Thread 적용

```
JVM threads: 225 → 30   ▼ 87%
p95:         1.85s → 2.06s  ▲ 악화
```

스레드 수는 줄었으나 p95는 오히려 증가했다. 두 가설 모두 기각됐다.

### 두 그래프의 불일치가 단서가 됐다

가설을 폐기하고 Grafana를 다시 확인했다. **pending은 줄었지만 p95는 줄지 않았다.** 두 그래프가 같이 움직이지 않는다는 점이 결정적인 신호였다. 커넥션이 병목이라면 두 지표가 동시에 개선되어야 했다.

폴링 구조를 다시 점검한 결과, `GET /api/queue/status` 1회당 DB 쿼리 3회(`countByConcertId`, `countByConcertIdAndStatus`, `findSeatIdsByConcertId`)가 발생하고 있었다. 풀을 늘리면 더 많은 폴링이 풀 슬롯을 점유하고, 점유한 만큼 DB로 흘러간다. 풀이 좁아서 큐에 갇혀 있던 폴링이 풀을 넓히는 즉시 DB로 풀려나가는 구조였다. 실제 병목은 DB 쿼리 빈도 자체였다.

### 해결 — 잔여석 캐시 (TTL=2s + 6곳 evict)

도메인 판단을 먼저 했다. 큐 상태 화면의 잔여석은 1~2초 늦은 근사값이어도 사용자 경험상 허용 가능하다. `SeatService.countAvailableSeatsForQueueStatus()`에 Redis 캐시를 적용하고, 캐시가 갱신되어야 하는 6개 시점에 `@CacheEvict`를 배치했다.

```java
@Cacheable(cacheNames = CacheNames.QUEUE_STATUS_AVAILABLE_SEATS, key = "#concertId")
public long countAvailableSeatsForQueueStatus(Long concertId) { ... }
```

evict 호출 6개 지점(코드 grep으로 확인):

- `HoldService:146` 홀드 생성
- `HoldService:166` 홀드 취소
- `HoldCleanupScheduler:107` 만료 정리
- `ReservationConfirmedEventListener:41` 예약 확정
- `ReservationService:208` 예약 환불
- `SellerService:188` 좌석 추가

### 결과

| 지표 | 캐시 전 | 캐시 후 | 변화 |
|------|---------|---------|------|
| p95 | 2.06s | 444ms | ▼ 78% |
| RPS | ~376/s | ~834/s | ▲ 122% (2.2배) |
| DB pending | 170까지 단조 적체 | 거의 0 | 적체 해소 |

단일 변경으로 p95 78% 감소, RPS 2.2배 증가가 확인됐다.

### 인정한 한계

캐시 적용 후 환경은 `pool=30 + VT on` 한 조건에서만 측정했다. 같은 환경에서 Virtual Thread off 비교는 수행하지 못했다. 2.2배 RPS 증가는 캐시 효과가 분명하지만, 그중 Virtual Thread의 기여도는 본 데이터로 분리할 수 없다.

</details>

<details>
<summary><b>Part B-2. Knee Point 탐색 — 측정 시나리오 자체가 부하를 만든다</b></summary>

목표는 VU=800 에러 0%와 VU=1500 에러 3.41% 사이의 변곡점을 찾아 SLO 기준을 확보하는 것이었다.

### 4회차 시행착오

| 회차 | 문제 | 원인 | 조치 |
|------|------|------|------|
| 1 | ramp 단계 진행 안 됨 | 기존 stress profile이 step 정의를 무시 | `knee-point.js` 신규 작성 |
| 2 | 진입 성공률 21% | `sleep(1)` 후 재시도로 1000명 동시 재시도 발생 → Rate Limiter가 공격 트래픽으로 인식 → 429 retry storm | `sleep(5)` + 지수 백오프 |
| 3 | 5xx 30% | `MAX_POLLS=1000`(4분)으로 단계 사이 VU 600 이상 누적 폴링 발생 | `MAX_POLLS=300`으로 축소 |
| 4 | 측정 성공 | — | — |

### Knee Point — 두 독립 신호의 일치

1. **k6**: `WARN[0178] EOF` 발생. 178초 시점부터 서버가 연결을 끊기 시작했다.
2. **Grafana**: 같은 178초 구간부터 RPS 곡선이 평탄해졌다.

178초는 VU 1000→1200 전환 시점이다.

본 측정 결과 안정 처리 상한은 VU=800 (≈1,447 RPS), Knee Point는 VU 1,000~1,200으로 확정했다.

</details>

<details>
<summary><b>Part B-3. 페일오버 — nginx 튜닝 가설이 측정으로 뒤집힌 사례</b></summary>

시나리오: VU=800 정상 부하 중 T+150s에 app1을 `docker kill`하고 T+180s에 `docker start`. 30초 다운 윈도우에서 사용자가 보는 에러율을 측정했다.

### baseline — 20% 에러는 구조적 결과

| 회차 | http_req_failed |
|------|-----------------|
| 1 | 20.74% |
| 2 | 20.80% |

두 회차의 차이는 0.06%p로, 측정 노이즈가 아닌 구조적 결과로 판단했다. passive health check는 실제 요청이 실패해야 격리를 시작하는 사후 대응 메커니즘이다. 격리 전까지 들어온 요청은 다운된 서버로 향한다. 수 %에서 수십 % 사이의 에러율은 passive HC의 구조적 하한이다.

### 첫 번째 가설 — nginx를 공격적으로 튜닝하면 에러가 감소할 것이다

| 변수 | Before | After |
|------|--------|-------|
| `max_fails` | 2 | 1 |
| `fail_timeout` | 10s | 5s |
| `proxy_connect_timeout` | 5s | 1s |

### 실측 — 가설 기각

| 지표 | baseline (A) | nginx 튜닝 단독 (B) |
|------|-------------|---------------------|
| http_req_failed | 20.77% | 24.51% |
| 진입 성공률 | (미측정) | 5% |

에러율이 +3.74%p 증가했다. 원인은 `max_fails=1`과 `connect_timeout=1s`의 조합이 VU=800 정상 부하에서 false-positive 격리를 유발한 것이었다. 정상 서버라도 부하가 몰리면 1초 내 connect 응답을 못 주는 순간이 발생하고, nginx는 이를 다운으로 판정한다. 트래픽이 다른 서버로 쏠리면서 그 서버에서도 같은 일이 발생해 연쇄 격리로 이어졌다.

### 변수 분리 매트릭스 — 진짜 개선 요인 찾기

같은 튜닝에 클라이언트 retry(최초 시도 + 재시도 2회, 지수 백오프)를 결합해 비교했다.

```javascript
// queue-flow-with-retry.js
const MAX_RETRIES = parseInt(__ENV.K6_RETRY_MAX || '2', 10);
const BACKOFF_BASE_MS = parseInt(__ENV.K6_RETRY_BASE_MS || '100', 10);

// 5xx와 connection error에만 재시도한다. 4xx는 재시도해도 동일하게 거부된다.
function isRetryable(status) {
  return status === 0 || (status >= 500 && status < 600);
}
```

| 조건 | nginx 튜닝 | retry | http_req_failed | 진입 성공률 |
|------|-----------|-------|-----------------|------------|
| A. baseline | OFF | OFF | 20.77% | — |
| B. 튜닝 단독 | ON | OFF | 24.51% | 5% |
| C. 결합 | ON | ON | 11.05% | 38% |

C만 봤다면 두 가지 모두 효과적이라고 결론낼 수 있었다. B가 baseline보다 나쁘다는 사실이 함께 있어 해석이 달라진다.

- nginx 튜닝 자체는 부작용을 만든다 (A→B: +3.74%p)
- retry가 그 부작용을 상쇄하고 추가 개선을 만든다 (B→C: -13.46%p)
- 결합 효과의 주된 기여는 retry에서 발생하고 nginx 튜닝은 보조 역할에 가깝다

### SLO 결론

오픈소스 nginx + passive HC 단독으로는 30초 다운 시 사용자 에러 ~20%가 구조적 하한이다. 클라이언트 retry로 11%까지 흡수 가능하지만, 사용자 노출 에러율 5% 미만을 SLO로 잡으려면 active health check(K8s, 클라우드 LB, nginx-plus 중 하나) 도입이 필요하다.

</details>

---

# 회고

4개월 동안 가장 자주 떠올린 문장은 "내가 만든 시스템을 내가 잘 모른다"였다. 코드가 동작한다는 사실과 시스템이 요구 성능을 만족한다는 사실은 다른 차원의 문제였고, 부하 테스트 한 번이 잘 찍혔다고 해서 SLO를 확정할 수 있는 것도 아니었다.

가설을 의심하는 감각을 익히는 데 가장 많은 시간을 썼다. B-1에서 pool 증설과 Virtual Thread를 연달아 기각당했는데, 두 가설 모두 직관적으로는 옳아 보였다. 두 그래프가 같이 움직이지 않는다는 신호를 처음에는 흘려보냈다. 데이터를 의심하는 일보다 자신의 가설을 신뢰하는 쪽이 자연스러웠기 때문이다. B-3 페일오버에서도 동일한 일이 반복됐다. "nginx를 공격적으로 튜닝하면 에러가 감소한다"는 답은 측정 전까지 가장 그럴듯하게 들렸지만 실제 결과는 정반대였다. 변수를 분리해 측정하지 않았다면 잘못된 운영 가이드를 그대로 적었을 것이다.

측정 인프라를 신뢰할 수 있게 만드는 일이 본 측정만큼 시간이 들었다. Prometheus가 app2를 인지하지 못한 일, Kafka 헬스 인디케이터가 헬스 응답을 막은 일, Grafana에서 p95가 표시되지 않은 일 모두 본 측정에 들어가기 전에 측정 도구부터 점검해야 한다는 사실을 매번 새로 알게 했다.

AI와 협업하는 방식에 대한 기준도 이번 프로젝트에서 정리했다. AI는 빠르고 표현이 매끄럽지만, 정답이 측정과 도메인 안에만 존재하는 영역에서는 일반론에 가까운 답을 내놓는다. Lua 원자성 근거 재정리, 200 OK 응답 코드 누락, nginx 튜닝 가설 기각, 문서 정직성 5건 수정은 모두 같은 결론을 가리켰다. 산출물의 사실 정확성은 산출 도구가 아니라 사용자가 책임진다는 것이다.

한계를 한계로 적는 일도 의외로 어려웠다. Virtual Thread 단독 기여도를 분리하지 못한 점, 단일 Redis 전제에서만 안전한 점, 잔여석 캐시가 Circuit Breaker 적용 범위 밖이라는 점, 클라이언트 retry가 k6에서만 동작한 점 모두 본문에서 빼고 싶었던 항목이다. 그래도 적어두기로 한 이유는 단순했다. 면접에서 코드를 열어보면 결국 드러날 부분이고, 먼저 명시하는 쪽이 정확한 검토를 가능하게 한다고 판단했기 때문이다.
