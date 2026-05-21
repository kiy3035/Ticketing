# 📌 프로젝트 개요

**"오픈 직후 1분 동안 트래픽이 평소의 수십 배로 들어와도, 같은 좌석을 두 명에게 팔지 않는 시스템을 만든다."**

콘서트 예매에는 닮아 보이지만 측정 방식이 완전히 다른 두 문제가 한 화면에 동시에 존재한다.

- **트래픽 폭주**: 오픈 순간 평소의 수십 배 요청이 단시간에 쏠리는 처리량·지연 문제
- **좌석 선점 경쟁**: 같은 좌석을 두고 다수가 경쟁하는 정확성·일관성 문제

이 프로젝트는 두 문제를 따로 분리해 각각의 지표로 검증했다. 처리량은 부하 테스트(k6 + Prometheus + Grafana)로 측정해 SLO 등급을 정의했고, 정확성은 단일 좌석 동시 선점 시나리오를 20회 독립 시행해 통계적으로 증명했다. 무엇보다 **"어떤 트레이드오프를 어떤 근거로 받아들였는가"** 를 한계까지 같이 문서에 남기는 데 시간을 썼다.

> 🛠️ **AI 협업 방식 안내**
> Claude·Cursor를 활용한 바이브 코딩 방식으로 진행. 보일러플레이트와 문서 초안은 AI에 맡겼고, 측정 실행·재현성 검증·트레이드오프 판단·운영 SLO 결정은 본인이 직접 수행했다. 본 문서의 수치는 모두 실측이며, 위임/직접 영역은 `AI 협업 분담`에 명시했다.

-- 시스템 아키텍처 섹션(사진)

-- CI/CD 파이프라인 섹션(사진)

---

# 🚀 한눈에 — 무엇을 만들었고 무엇을 증명했는가

**핵심 설계 3가지**
1. **Redis 분산 락(SETNX + UUID 토큰 + Lua)** — 좌석 단위 잠금 + 소유자 토큰 검증 + Lua 원자 해제. Redisson 미사용
2. **잔여석 캐시(`@Cacheable` TTL=2s + 6곳 evict)** — DB 폴링 빈도 병목 해소
3. **앱 2대 + nginx `least_conn`** — 분산 환경에서도 락 무결성 유지

**증명한 수치**

| 항목 | 결과 |
|------|------|
| 좌석 동시 선점 정확성 | VU=100, 20회 독립 시행 **모두 정확히 1건** 성공 |
| 안정 운영 상한(Normal) | VU=800 (≈ **1,447 RPS**), p95 **164ms**, 에러 **0%** |
| 캐시 도입 효과 | p95 2.06s → **444ms (▼78%)**, RPS 376 → **834/s (▲122%)** |
| Knee Point | VU **1,000~1,200** (k6 EOF + Grafana RPS 평탄화 동시점) |
| 페일오버 ablation | nginx 튜닝 단독 **+3.74%p 악화**, retry 결합 -47% — 실질 개선 주역은 retry |

**정직성으로 적은 한계** — VT 단독 기여도 미분리 · 단일 Redis 전제 · 잔여석 캐시는 CB 적용 범위 밖 · 클라이언트 retry는 k6에서만 측정.

---

# 🎯 운영 SLO — 부하 등급별 분리 정의

단일 임계값으로 "통과/실패"를 가르는 대신 **부하 등급마다 별도 SLO**를 정의했다. 가장 자주 일어나는 Normal은 까다롭게, 드물게 발생하는 Failover는 현실적 한계까지 인정하는 쪽이 정직한 운영 기준이라고 봤다.

| 등급 | 부하 조건 | P95 | 에러율 | 시스템 동작 | 근거 측정 |
|------|----------|-----|-------|------------|----------|
| **Normal** | VU ≤ 800 (≈1,447 RPS) | < 500ms | < 1% | 풀 응답·캐시 ON | 2대 nginx 측정: p95 164ms, RPS 1,447/s, 에러 0% |
| **Degraded** | VU 800~1,200 (knee 구간) | < 2s | < 5% | 잔여석 캐시 hit률 유지, 일부 폴링 응답 지연 발생 | Knee Point 탐색 측정: p95 494ms, 에러 4.90% |
| **Failover** | 앱 1대 다운 30초 윈도우 | < 1s | retry 적용 시 ~11%, 미적용 시 ~20% | passive HC + nginx `proxy_next_upstream` 재시도 | 페일오버 baseline 20.77%, k6 retry 결합 시 11.05% |
| **Hard limit** | VU ≥ 1,500 | timeout 발생 | n/a | 대기열 차단·입장 제한 | VU=1500 한계 부하 측정: max 40s, 에러 3.41% |

> **가용성 퍼센티지(99.X%)는 산출하지 않는다.** 단발 부하 시나리오는 한 달 가용성 계산에 표본이 부족하다. 대신 **Failover 윈도우에서 사용자 노출 에러율**을 가용성 대리 지표로 정량화했다.

> **P95를 메인 SLO로 쓴 이유 (P99 거부)**
> - **표본 안정성**: P99는 가장 느린 1% 응답이라 GC 일시 정지·네트워크 지터(jitter) 1~2건에도 값이 크게 흔들린다. k6 1회 시행으로는 신뢰 구간이 너무 넓어, 캐시 도입 78% 개선처럼 단일 변수 효과를 측정할 때 노이즈가 결론을 가린다. P95는 상위 5%를 잘라낸 값이라 같은 환경에서 다시 재면 분산이 작다.
> - **등급 분기 가능성**: Normal(< 500ms) / Degraded(< 2s) / Hard limit(timeout) 3등급 경계를 그어야 했는데, P99로 보면 모든 등급에서 한계치 근처라 등급별 차이가 안 갈린다.
> - **인정한 한계**: 가장 느린 1% 사용자 경험은 P95로는 안 보인다. **P99·Max는 Grafana 패널에 같이 띄워 보조 지표로만 추적**, 메인 SLO에서는 제외. 실서비스 결제 흐름이라면 P99도 SLO에 포함시키는 게 맞다.

> **장애 대응 메커니즘**: Redis 직접 호출(`HoldStore`·`QueueService`)에 Resilience4j `redisCircuitBreaker` 적용 — OPEN 시 호출 차단 + 작업별 fallback(생성=실패, 조회=null/empty). **잔여석 캐시(`@Cacheable`)는 CB 적용 범위 밖**(Spring `RedisCacheManager` 기본 동작 — 인정한 한계). 락 실패는 429 + `nginx.conf proxy_next_upstream`로 자동 재시도. **에러율 5% 미만 SLO 목표**를 잡으려면 active HC(K8s·클라우드 LB·nginx-plus) 도입 필수.

---

# 🎚 주요 트레이드오프 — 한 표로

각 결정의 **선택 / 거부한 대안 / 근거 / 인정한 한계**.

| 영역 | 선택 | 거부한 대안 | 근거 | 인정한 한계 |
|------|------|------------|------|------------|
| 분산 락 | SETNX + UUID 토큰 + Lua | Redisson(Redlock) | 단일 Redis 인스턴스에선 분산 합의가 무의미. 토큰 + Lua로 등가 안전성 확보 | Sentinel/Cluster 전환 시 라이브러리 재검토 |
| 로드밸런서 | nginx 단독 (passive HC) | ALB / nginx-plus active HC | 인프라 비용·운영 단순성 | 30초 다운 시 사용자 에러 ~20% 구조적 하한 |
| 잔여석 동시성 | `@Cacheable` TTL=2s + 6곳 evict | 강한 일관성(실시간 COUNT) | 잔여석은 1~2초 근사가 도메인상 허용. p95 78% 감소·RPS 2.2배 효과 측정 | evict 누락 시 stale 노출 위험 (현재 6곳 grep으로 일치 확인) |
| 스레드 모델 | Virtual Thread (Java 21) | 플랫폼 스레드 + 풀 확대 | IO-bound 폴링에 적합. JVM threads 225→30 실측 | VT 단독 기여도 분리 측정 미완성 |
| 메시지큐 | Kafka acks=all + idempotence + DLT 3회 | RabbitMQ | "한 번 이상 + 순서·재처리" 패턴에 적합 | DLT 모니터링 자동화 미구현 |
| 트랜잭션 경계 | Saga + REQUIRES_NEW | 분산 트랜잭션(XA) | 결제 단계 격리, 외부 PG 호출과 DB 트랜잭션 분리 | 보상 로직 검증 배치 별도 필요 |
| 락 TTL | 3초 (외부화) | 1초 / 10초 | 정상 흐름 1초 미만 측정 후 3배 마진 | 보유자 비정상 종료 시 3초간 자원 고립 |
| Failover 회복 | passive HC + nginx `proxy_next_upstream` 재시도 | active HC (K8s 등) | 무료 nginx + 인프라 단순화 우선 | **에러율 5% 미만 목표는 미달성**(현재 Failover 등급 에러율 ~20%). 클라이언트 측 retry는 k6에서만 측정, 실 프론트엔드 미구현. active HC 필요 |
| 서킷브레이커 | 단일 CB (read·write 공용) | read/write CB 분리 | 단일 Redis 노드 전제 — 장애 시 동시 영향이라 분리 실익 낮음. fallback은 호출 지점별로 이미 분리 | 정책 분리(read 빨리 열기 / write 보수적)는 미적용. Sentinel/Cluster 전환 시 재검토 |
| 운영 지표 | P95 + 에러율 분리 SLO | 단일 P99 또는 단일 임계 | 등급별로 요구가 달라 단일 지표로는 의사결정 불가 | P99·Max는 부수 추적만, 메인 SLO에서 제외 |

---

# 🧱 기술 스택 버전 선택 근거

스택 자체보다 **왜 그 버전인지**가 더 자주 묻는 질문이라 따로 정리했다. 출발점은 **Java 21 (Virtual Thread)**이고 나머지는 거기서 자연스럽게 따라온 선택이다.

| 기술 | 버전 | 선택 근거 | 거부한 대안 |
|------|------|----------|------------|
| **Java** | **21 (LTS)** | Virtual Thread가 정식 안정화된 첫 LTS. IO-bound 폴링이 핵심 워크로드라 VT 효과 직접 측정 가능 (JVM threads 225→30 실측) | 17(VT가 preview 단계라 운영 투입 부담) / 25(출시 직후라 운영 사례 부족) |
| **Spring Boot** | **3.4.1** | Java 21 + VT 자동 설정(`spring.threads.virtual.enabled=true`) 지원 안정판. Jakarta EE 9+ 기반 | 2.7.x(Java 17 미만 호환, EOL 임박) |
| **MySQL** | **8.0** | `SKIP LOCKED`·CTE 등 동시성 쿼리 지원, RDS 호환성, 운영 사례 풍부 | 5.7(EOL) / 8.4 LTS(릴리스 직후라 운영 검증 부족) |
| **Redis** | **7.2** | 락·캐시·rate-limit·대기열·JWT 블랙리스트를 1대로 처리. Functions·메모리 효율 개선 | 6.x(가능하나 7로 통일해 신기능 옵션 확보) |
| **Kafka** | **3.8.1** (Confluent 7.6.1) | 결제 완료·홀드 이벤트 비동기 분리. **retention·리플레이** 필요한 시나리오라 Redis Stream보다 보관·재처리에 유리 | RabbitMQ(라우팅은 강하나 순서·재처리 패턴이 약함) |
| **Resilience4j** | **2.2.0** | Hystrix는 maintenance mode. 함수형 API + Spring Boot Actuator 통합 | Hystrix(deprecated) |
| **Flyway** | (Spring Boot 관리) | DB 스키마 버전 관리 표준. Spring Boot 자동 연동 | Liquibase(XML 중심이라 SQL 가시성 낮음) |

> **한 줄 요약**: Java 21을 고른 게 출발점. VT를 쓰려면 21 LTS, 그러려면 Boot 3.x, 그러려면 Jakarta EE 9+ — 나머지는 자연스럽게 따라온 선택이다. 최신만 좇은 게 아니라 **워크로드(IO-bound 폴링 + 좌석 동시성)에 가장 직접적인 효과가 있는 버전 조합**을 골랐다.

---

# 🤝 AI 협업 분담

**AI(Claude/Cursor)에 맡긴 영역**

- **보일러플레이트**: DTO·Controller·Repository 스캐폴드, Bean Validation
- **Lua 스크립트 초안**: SETNX·EXISTS·ZADD 결합 (이후 본인이 원자성 가설로 재검토)
- **문서·주석 1차**: README, ADR 초안, JavaDoc 표현
- **가설 후보 발산**: "pool 늘려보자", "VT 켜보자" 등 시도 후보군
- **엣지 케이스 발산**: 놓친 시나리오 후보 제시

**본인이 직접 수행한 영역**

- **아키텍처 결정**: Redisson 미도입(단일 Redis 전제), nginx 단독(ALB 미사용), Saga REQUIRES_NEW, JWT 블랙리스트
- **임계치 산정**: 락 TTL 3초(정상 1초 + 3배 마진), 캐시 TTL 2초(근사 허용), pool 30(실측 도출)
- **부하 테스트 설계·실행**: k6 4종 작성, 4회차 시행착오 디버깅
- **가설 기각·ablation**: pool→VT 두 가설 기각 후 폴링 빈도가 진짜 병목임 도출. nginx 튜닝 단독은 +3.74%p 악화임을 변수 분리로 증명
- **재현성 검증**: 핵심 측정 최소 2회, Part A는 20회 반복
- **트레이드오프·한계 명시**: 단일 Redis 전제, VT ablation 미완성, 5% 미만 SLO에 active HC 필요, 잔여석 캐시 CB 적용 범위 밖 등

---

# 🛠️ 트러블슈팅 및 검증 사례

### [핵심 가치]
- **데이터 정합성 검증** — 분산 락으로 멀티 인스턴스 환경에서 좌석 중복 선점 가능성을 0%로 통제함을 통계적으로 증명
- **데이터 기반 진단** — 부하 테스트를 통해 시스템 임계점·병목 구간을 정량적으로 식별·개선

---

## 🔒 Part A. 멀티 인스턴스 환경의 동시성 제어 (Redis SETNX 분산 락)

### 1. 문제 정의
- **환경**: 애플리케이션 서버 2대 + Nginx 로드밸런서
- **과제**: 동일 좌석에 동시 선점 요청이 수렴할 때 정확히 1개만 성공
- **검증 방향**: 코드 레벨의 낙관적 예측을 배제, 부하 테스트로 통계적 증거 확보

### 2. 아키텍처 설계 — 선택과 거부

**선택**: SETNX(setIfAbsent) + UUID 토큰 + Lua 원자 해제
**거부**: Redisson(Redlock 기반)
**근거**: 단일 Redis 인스턴스 환경에서 Redlock의 분산 합의 알고리즘은 동작 의미가 없음. 토큰 + Lua 두 가지로 단일 노드 무결성은 동등하게 확보 가능. 의존성·학습곡선 비용도 낮음.

#### 좌석 단위 락 키 + UUID 토큰

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

#### TTL 3초 — 비즈니스 측정 기반

정상 프로세스(Redis 호출 + DB 반영) TAT가 1초 미만임을 측정으로 확인. 3배 마진으로 락 보유자의 비정상 종료에서 발생할 자원 고립을 막으면서, 정상 처리 도중 락이 먼저 만료되는 'Lock 릴리즈 현상'도 차단했다.

```properties
# 정상 흐름(Redis 4회 + DB 1회)이 1초 내 종료되는 것 기준 3배 여유
ticketing.lock.ttl-seconds=3
```

#### Lua 스크립트로 좌석 상태 전이 원자화

좌석→토큰, 토큰→홀드 정보, 만료 ZSET을 단일 Lua 트랜잭션으로 처리. 중간 연산 개입을 원천 배제한다.

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

### 3. 검증 시나리오

| 항목 | 값 |
|------|------|
| 도구 | k6 (shared-iterations executor) |
| VU | 100 |
| 총 iteration | 100 (VU당 평균 1회) |
| 대상 좌석 | 단일 좌석 (`K6_HOT_SEAT_ID=9000`) |
| 구성 | 1대 직접 / 2대 nginx 분산 |
| 반복 | 각 구성 10회씩, 총 **20회 독립 시행** |
| 회차 간 초기화 | `redis-cli FLUSHDB` + 좌석 DB `status=AVAILABLE` 복원 + `seat_hold` 삭제 |

### 4. 결과

| 응답 | 1대 직접 (10회 합) | 2대 nginx (10회 합) |
|------|------------------|--------------------|
| 201 선점 성공 | **모든 회차 정확히 1건** | **모든 회차 정확히 1건** |
| 5xx | 0건 | 0건 |
| 409 이미 선점 | 0~7건 | 0~21건 |
| 429 락 경합 | 92~99건 | 78~99건 |

20회 전부 201 = 1건. Redis가 단일 잠금 저장소이므로 요청이 nginx를 거쳐 2대에 분산되어도 락의 원자성이 유지된다는 사실이 통계적으로 확인된다. 409·429 비율은 회차마다 달라지지만 두 응답 모두 중복 선점을 막는 올바른 동작이다(락 단계에서 걸리면 429, 락 통과 후 이미 선점 상태 확인이면 409).

### 5. 부수 발견 — HoldController 응답 코드 버그

초기 검증에서 `201 체크`가 0건으로 잡혔지만 `http_req_failed` 역산 시 1건은 비실패. 추적 결과 `HoldController.createHold()`가 `ResponseEntity` 없이 `HoldResponse`를 직접 반환해 Spring이 기본 200 OK를 적용한 것이 원인. 한 줄로 수정.

```java
// HoldController.java
@PostMapping
@ResponseStatus(HttpStatus.CREATED)  // 부하 테스트 중 발견·추가
public HoldResponse createHold(Authentication authentication, @Valid @RequestBody HoldRequest request) {
    return holdService.createHold(request, authentication.getName());
}
```

응답 코드 분포까지 셈한 덕에 우연히 잡힌 결함.

### 6. 인정한 한계

Redisson 미도입은 단일 Redis 인스턴스 전제에서만 유효하다. Sentinel/Cluster로 전환하면 Redlock이 필요한 시점이 오고, 그때는 라이브러리 선택을 재검토해야 한다. 본 검증은 **단일 Redis 환경의 통계적 증거**일 뿐, 모든 분산 환경의 안전을 증명한 것은 아니다.

> 💭 **느낀점**
> - 정확성은 "한 번 통과"가 아니라 "20번 독립 시행 전부 통과"여야 의미가 생긴다
> - k6 기본은 4xx를 실패로 집계. 409·429가 정상 응답인 도메인에선 `http_req_failed: rate<1` 임계로 명시 — 도구의 디폴트가 도메인과 맞지 않으면 디폴트를 의심해야 한다
> - 통과/실패만 보지 않고 응답 코드 분포까지 셈한 덕에 200 OK 버그도 잡혔다

---

## ⭐ Part B. 부하 테스트로 본 시스템 진단·개선

### 🧰 측정 가능한 시스템 만들기 — 부하 테스트 인프라 구축

본 측정 사례 들어가기 전에, **측정 인프라 자체를 구축하는 과정**부터 정리한다. 결과만 보여주면 "그래서 어떻게 측정했나"가 빠진다.

#### 1. Grafana p95가 평탄선 — Prometheus 히스토그램 누락

초기에 `http_req_duration` p95를 Grafana 패널에 띄우려 했는데 그래프가 평탄선만 표시. `histogram_quantile()`이 요구하는 `*_bucket` 시리즈가 Prometheus에 없는 게 원인. Spring Boot Actuator는 기본적으로 percentile 메트릭만 발행하고 bucket 시리즈는 옵트인이다.

```properties
# HTTP 지연 p95: Prometheus에 *_bucket 시리즈를 보내야 histogram_quantile 사용 가능
management.metrics.distribution.percentiles-histogram.http.server.requests=true
```

이 한 줄을 추가한 뒤에야 `histogram_quantile(0.95, sum by(le)(rate(http_server_requests_seconds_bucket[1m])))` 가 동작했다.

#### 2. 부하 시 Kafka 헬스 인디케이터가 60초 타임아웃

`/actuator/health` 호출이 부하 중 60초에 한 번씩 멈춤. Spring Boot 기본 Kafka 헬스 인디케이터가 broker `metadata` 호출을 동기로 수행하는데, 부하 상황에서 응답 지연이 헬스 체크 전체를 막아 nginx 헬스 체크까지 영향. 진단 후 비활성화.

```properties
# 부하 시 Kafka indicator가 60초 타임아웃 유발 → 헬스 응답 막힘
management.health.kafka.enabled=false
```

#### 3. Prometheus가 app2를 모르고 있었다 (페일오버 베이스라인 측정 중 발견)

페일오버 baseline 1회차 측정 도중 server1을 docker kill하자 **Grafana 패널이 모두 공백**. 그런데 k6 합산 RPS는 끊기지 않음. 두 신호의 불일치가 단서였다. 확인해 보니 `prometheus.yml scrape_configs`에 server1 IP만 등록돼 있었다. server2 추가 후 1회차 폐기, 재측정.

> **측정 인프라가 측정 대상의 가용성에 의존하면 가장 필요한 순간에 데이터가 사라진다.** 이후 부하 테스트 체크리스트에 "타겟이 모든 인스턴스를 잡고 있는가" 항목 추가.

#### 4. 비즈니스 메트릭 커스텀 등록

서버 헬스만으로는 도메인 행동을 추적할 수 없어 핵심 지점에 Micrometer 커스텀 카운터를 직접 등록했다.

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

홀드 성공·락 경합·Lua 중복 검출을 각각 별도 카운터로 분리. Grafana에서 "락 실패가 늘면서 진입은 줄었나"를 한 패널에서 보게 됐고, 부하 테스트 해석의 근거 데이터로 작동한다.

> 💭 **측정 인프라에서 배운 것**
> - 부하 테스트는 k6 한 줄 실행이 아니라 **k6 → Prometheus → Grafana** 세 도구가 같은 사실을 가리켜야 신뢰할 수 있다
> - 헬스 체크 자체가 부하 원인이 될 수 있다 (Kafka indicator)
> - "왜 이 그래프가 비어 있지?"가 가장 먼저 떠올라야 한다 (Prometheus scrape 누락)

---

### B-1. p95가 떨어지지 않는다 — 풀과 스레드를 다 바꿔도 안 풀리던 문제

초기 측정: VU 800으로 큐 폴링 API → **p95 = 1.93초**. 사용자 입장에선 "고장났다" 수준.

Grafana 신호: `hikaricp_connections_active`가 10에 평탄(풀 한계 도달), `hikaricp_connections_pending`이 170까지 단조 증가. 풀이 들어오는 요청을 따라잡지 못한다고 판단.

#### 첫 가설 — Hikari pool 10 → 30

| 지표 | pool=10 (기준선) | pool=30 | 변화 |
|------|-----------------|---------|------|
| p95 | 1.93s | 1.85s | ▼ 80ms (-4.1%) |
| RPS | ~408/s | ~386/s | ▼ 22/s (-5.4%) |
| 에러율 | 0% | 0% | - |
| JVM threads | ~225개 | ~225개 | - |
| DB pending | 0→170 단조 적체 | 적체 패턴 유지 | 적체 해소 안 됨 |

> 조건: 캐시 미적용, VU=800, `K6_QUEUE_POLL_SLEEP_SEC=0.005`, JVM hot 2·3회차 중앙값

풀을 3배로 늘렸는데 사실상 차이가 없다. 더 결정적인 신호는 DB pending 적체가 그대로 유지된 점. 커넥션을 더 줘도 DB가 받는 쿼리 자체가 너무 많아 큐가 풀리지 않는다.

#### 두 번째 가설 — Virtual Thread

```
JVM live threads: 225 → 30        ▼ 87%
p95:              1.85s → 2.06s   ▲ 악화
RPS:              386 → 376/s
```

스레드는 줄었지만 p95는 오히려 올라갔다. 두 가설 모두 빗나감.

#### 두 그래프의 불일치가 단서였다

가설을 폐기하고 Grafana를 다시 봤다. **pending은 줄었는데 p95는 안 줄었다.** 두 그래프가 같이 움직이지 않는다는 사실이 결정적이었다.

> pending이 줄어도 p95가 안 줄면, 커넥션 자체는 병목이 아니다.

폴링 구조를 다시 짚어보니 `GET /api/queue/status` 1회마다 DB 쿼리 3회 실행(`countByConcertId`, `countByConcertIdAndStatus`, `findSeatIdsByConcertId`). 풀을 늘리면 더 많은 폴링이 풀 슬롯을 점유하고 그대로 DB로 흘러간다. 풀이 좁아서 큐에 갇혀 있던 폴링이 풀을 넓히면 그대로 DB로 풀려나가는 구조. **진짜 병목은 DB 쿼리 빈도.**

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
# GET /api/queue/status의 availableSeatCount 집계 TTL(초). 홀드/예약/만료/좌석 추가 시 evict 병행
ticketing.cache.queue-status-available-seats-ttl-seconds=2
```

evict 호출 6개 지점(실제 코드 기준):
- `HoldService:146` — 홀드 생성 직후
- `HoldService:166` — 홀드 취소
- `HoldCleanupScheduler:107` — 만료 홀드 정리
- `ReservationConfirmedEventListener:41` — 예약 확정 이벤트
- `ReservationService:208` — 예약 환불
- `SellerService:188` — 좌석 추가

#### 캐시 전후 비교

| 지표 | 캐시 전 (pool=30 + VT) | 캐시 후 (pool=30 + VT + Cache) | 변화 |
|------|----------------------|-----------------------------|------|
| p95 | 2.06s | **444ms** | **▼ 78%** |
| RPS | ~376/s | **~834/s** | **▲ 122%** (2.2배) |
| DB pending | 높음 (170까지 단조 적체) | **거의 0** | 적체 해소 |
| 에러율 | 0% | 0% | - |

> 조건: VU=800, batch-size=50, JVM hot 2·3회차 중앙값. 캐시 전 데이터는 같은 환경(pool=30 + VT)에서 캐시만 끈 측정을 기준선으로 사용해 캐시 단일 변수만 비교.

**변경 1개로 p95 78% 감소, RPS 2.2배.** 이 프로젝트에서 가장 임팩트가 큰 단일 변경이었다.

#### 트레이드오프 — 강한 일관성 대신 1~2초 근사

캐시 도입은 무료가 아니다. 잔여석은 최대 TTL 2초 + evict 지연만큼 stale 가능하다. 결제 직전 좌석 상태처럼 강한 일관성이 필요한 지점에는 캐시를 쓰지 않고, 노출 빈도가 높고 약간 늦어도 무방한 "큐 상태 잔여석 표시"에만 적용했다. 이 도메인 분리가 캐시 도입을 가능하게 한 전제다.

#### 인정한 한계

캐시 후 환경은 `pool=30 + VT on` 한 조건만 측정. 같은 환경에서 VT off 비교는 진행하지 않았다. 2.2배 RPS 증가는 캐시 효과가 맞지만, 그 안에 Virtual Thread가 얼마나 기여했는지는 이 데이터만으로 분리 불가. B-3에서 변수를 분리한 것과 대비되는 미완성 측정이다.

> 💭 **느낀점**
> - 직관적 가설을 두 번 시도하고 두 번 다 틀린 게 핵심 경험. 데이터 없이 풀을 60, 100까지 늘렸으면 영영 못 풀었을 것
> - 결정적 신호는 두 그래프의 불일치. 한 그래프만 봤으면 못 잡았을 신호
> - 캐시는 만능이 아니라 도메인 판단의 결과. "잔여석 1~2초 근사 허용"이라는 비즈니스 조건이 없었으면 못 썼다

---

### B-2. 측정 도구가 측정 대상이 되어 버린 — Knee Point 탐색

목표: VU=800에서 에러 0%, VU=1500에서 에러 3.41%. 그 사이 변곡점을 찾아 운영 SLO 기준을 확보.

도구는 그대로지만 **시나리오 자체가 조작 가능한 부하 생성기**임을 깨닫는 시행착오.

| 회차 | 문제 | 원인 | 조치 |
|------|------|------|------|
| 1 | ramp 안 되고 한 번에 피크 | 기존 `queue-flow.js` stress profile이 step 정의 무시 | `knee-point.js` 신규 작성 |
| 2 | 진입 성공률 21% | `sleep(1)` 후 재시도 → 1000명 동시 재시도 → Rate Limiter가 공격 인식 → 429 retry storm | `sleep(5)` + 백오프 |
| 3 | 5xx 30% | `MAX_POLLS=1000`(4분) → 단계 사이 VU 600+ 누적 폴링 | `MAX_POLLS=300`으로 축소 |
| 4 | 측정 성공 | — | — |

최종 시나리오:

```javascript
// knee-point.js
export const options = {
  stages: [
    { duration: '1m',  target: 500  },  // 워밍업 — JVM warm + 안정 베이스라인
    { duration: '1m',  target: 800  },  // 안정 운영 상한
    { duration: '1m',  target: 1000 },  // 탐색 구간
    { duration: '1m',  target: 1200 },  // 탐색 구간
    { duration: '1m',  target: 1500 },  // 한계 부하
    { duration: '30s', target: 0    },
  ],
  thresholds: {
    http_req_duration: ['p(95)<120000'],
    http_req_failed:   ['rate<1'],  // knee 탐지용 — 에러 나도 중단하지 않음
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

#### Knee Point — 두 독립 신호가 같은 시점을 가리켰다

1. **k6 클라이언트**: `WARN[0178] EOF` — 178초 시점부터 서버가 연결을 끊기 시작. 178초는 VU=1000→1200 전환 시점과 정확히 일치
2. **Grafana 서버**: 같은 178초 구간부터 RPS 곡선이 평탄해짐

**SLO 기준 확정**: t3a.small 2대 + nginx에서 안정 처리 상한 VU=800(≈1,447 RPS), Knee Point는 VU=1,000~1,200. 위 SLO 표에 그대로 반영.

> 💭 **느낀점**
> - 측정 도구도 측정 대상이다. 시나리오의 sleep·polling 횟수·재시도 정책 하나하나가 서버 부하 패턴을 결정
> - 2회차 Rate Limiter retry storm은 방어 메커니즘이 클라이언트 동작과 결합해 의도치 않은 양상을 만든 사례
> - "변곡점을 찾았다"보다 "두 독립 신호(k6 EOF + Grafana RPS 평탄화)가 같은 구간을 가리켰다"는 검증 감각이 더 값졌다

---

### B-3. nginx 튜닝이 답일 줄 알았다 — 변수 분리로 뒤집힌 페일오버 가설

시나리오: VU=800 정상 부하 중 T+150s에 app 서버 1을 `docker kill`, T+180s `docker start`. 30초 다운 윈도우에서 사용자가 보는 에러율 측정.

#### baseline — 20% 에러는 구조적 결과였다

| 회차 | http_req_failed |
|------|-----------------|
| 1 | 20.74% |
| 2 | 20.80% |

두 회차 차이 0.06%p. 측정 노이즈가 아닌 구조적 결과다. **passive health check는 진짜 요청이 실패해야 격리가 시작되는 사후 대응 메커니즘**이라, 격리 전까지 들어온 요청은 죽은 서버로 향한다. 수 %~수십 % 에러는 passive HC의 구조적 하한.

#### 첫 가설 — nginx를 더 공격적으로 튜닝하면 에러가 줄 것이다

`nginx.conf` 변경:

```nginx
upstream ticketing_app {
    least_conn;
    # max_fails=1 fail_timeout=5s — 빠른 격리(2회→1회, 10s→5s)로 페일오버 시 사용자 에러 노출 단축
    # 트레이드오프: 정상 부하의 일시적 응답 지연·GC pause를 false-positive로 격리할 위험
    server 172.31.46.152:8080 max_fails=1 fail_timeout=5s;
    server 172.31.37.7:8080 max_fails=1 fail_timeout=5s;
}

location / {
    proxy_pass http://ticketing_app;
    # connect 1s — 죽은 서버 connection refused/SYN drop을 빠르게 감지
    proxy_connect_timeout 1s;
    # 서버 장애 시 자동으로 다른 서버에 재시도
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
|------|-------------|---------------------|
| http_req_failed | 20.77% | **24.51%** ⚠️ |
| 진입 성공률 | (미측정) | **5%** |

에러율이 오히려 +3.74%p 증가, 진입 성공률 5%로 폭락.

**원인**: `max_fails=1` + `connect_timeout=1s`가 VU=800 정상 부하에서 **false-positive 격리** 트리거. 정상 서버라도 부하가 몰리면 1초 안에 connect 응답을 못 주는 순간이 있고 nginx는 그걸 "죽었다"로 판정. 트래픽이 다른 서버로 몰려 그 서버도 같은 일이 벌어진다 → **연쇄 격리**. 이 위험은 `nginx.conf` 주석에 미리 적어 두긴 했지만, 그게 실제 부하 환경에서 측정으로 확정된 게 핵심.

#### 변수 분리 매트릭스로 진짜 주역 찾기

같은 튜닝에 클라이언트 retry(최초 시도 + 재시도 2회, 총 3번)를 결합.

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

| 조건 | nginx 튜닝 | retry | http_req_failed | 진입 성공률 |
|------|-----------|-------|-----------------|------------|
| A. baseline | OFF | OFF | 20.77% | — |
| B. 튜닝 단독 | ON | OFF | 24.51% ⚠️ | 5% |
| C. 결합 | ON | ON | **11.05%** ✅ | 38% |

C가 baseline보다 좋아진(-47%) 결과만 봤다면 둘 다 효과적이었다고 결론낼 뻔. 하지만 **B가 baseline보다 나쁘다**는 사실이 같이 있어 해석이 달라진다.

- nginx 튜닝 자체는 부작용 (A→B: +3.74%p)
- retry가 그 부작용을 상쇄하고 추가 개선 (B→C: -13.46%p)
- **결합 효과의 주역은 retry, nginx 튜닝은 보조**

#### 운영 SLO 결론 (다시 위 SLO 표와 연결)

오픈소스 nginx + passive HC 단독으로는 30초 다운 시 사용자 에러 ~20%가 구조적 하한. 클라이언트 retry로 11%까지 흡수 가능하나, **사용자 노출 에러율 5% 미만을 SLO 목표로 잡으려면 active health check(K8s, 클라우드 LB, nginx-plus 중 하나) 도입이 필수**다. 이 결론을 SLO 표의 Failover 등급 에러율(retry 미적용 시 ~20%)과 "에러율 5% 미만은 비목표" 한계로 그대로 반영했다.

> 💭 **느낀점**
> - 그럴듯한 가설과 맞는 가설 사이 거리. "nginx 공격적 튜닝 → 격리 빠름 → 에러 감소"는 누구나 끄덕일 만한데 측정 전까진 정답이 아니었다
> - 변수를 분리하지 않고 결합 효과만 봤으면 잘못된 운영 가이드를 작성했을 것
> - Prometheus 타겟 누락 사건 이후 체크리스트에 "타겟이 모든 인스턴스를 잡고 있는가" 항목을 추가

---

# 🎯 마지막 느낀점

4개월 동안 가장 자주 한 생각은 "내가 만든 시스템을 내가 잘 모른다"였다. 코드가 돌아간다는 게 시스템이 잘 돌아간다는 뜻이 아니었고, 부하 테스트가 한 번 잘 찍혔다고 SLO를 잡을 수 있는 것도 아니었다.

B-1에서 두 가설을 연달아 기각당한 게 컸다. pool을 늘리고 VT를 켜는 건 너무 직관적으로 맞아 보였는데, 그래프를 다시 보니 두 변경 다 진짜 병목과 무관한 데를 건드리고 있었다. 두 그래프가 같이 안 움직인다는 신호가 결정적이었는데 처음엔 그걸 그냥 지나쳤다. 데이터를 의심하는 감각보다 내 가설을 믿고 싶은 마음이 더 셌다.

B-3 페일오버도 비슷했다. "nginx 더 공격적으로 튜닝하면 빨리 격리되겠지"가 너무 그럴듯해서 의심 없이 실험을 시작했는데, 변수를 분리해서 측정하니 오히려 baseline보다 나빠졌다. 그 한 번을 안 했으면 잘못된 결론을 운영 가이드로 적었을 것이다. 이후로는 그럴듯한 가설이 가장 위험하다는 걸 머리에 새기고 측정한다.

부하 테스트 인프라 셋업이 의외로 가장 손이 많이 갔다. Prometheus가 app2를 모르고 있던 거, Kafka 헬스 인디케이터가 헬스 응답을 막던 거, Grafana에서 p95가 안 찍히던 거 — 본 측정 전에 측정 도구부터 고쳐야 한다는 사실을 매번 새로 배웠다. "측정 인프라가 측정 대상의 가용성에 의존하면 안 된다"는 문장은 그 사이에 직접 데인 결과다.

AI랑 같이 일하는 방식도 계속 다듬었다. 코드를 부탁하는 건 빠르지만 그 코드가 맞는지 확인하는 건 본인 몫이다. 노션 본문 검수에서 다섯 군데 부정확한 표현을 잡아냈는데, 측정 데이터·코드 grep으로 일일이 확인하지 않았으면 그대로 발표했을 것이다. AI는 빠르고, 동시에 틀려도 자신감 있게 틀린다.

수치보다 기억에 남는 건 한계를 한계로 적는 일이 의외로 어려웠다는 점이다. VT 단독 기여도를 분리 못 한 부분, 단일 Redis 전제에서만 안전한 부분, 잔여석 캐시가 CB 적용 범위 밖인 부분, 클라이언트 retry는 k6에서만 측정한 부분 — 본문에서 빼버리고 싶었던 적이 한 번씩 다 있었다. 숨기는 쪽이 발표하기엔 더 매끄럽기 때문이다. 그래도 적어두기로 한 건 면접에서 코드를 열어보면 결국 드러날 부분이라, 먼저 인정하는 쪽이 낫다는 단순한 판단이었다. 이런 결정을 매번 해야 했던 게 이번 프로젝트의 진짜 어려움이었다.
