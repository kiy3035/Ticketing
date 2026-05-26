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

전자는 검증 비용이 낮다. 컴파일·테스트·문서로 확인 가능하기 때문이다. 후자는 측정 데이터와 도메인 이해가 없으면 정답에 닿을 수 없다. AI는 후자 영역에서 가설 후보를 빠르게 던지는 역할만 했고, 채택과 기각은 측정으로 직접 판단했다(부하 테스트 dropdown의 Part B-1·B-3 참조).

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
- 가설 검증과 기각 — pool 증설·Virtual Thread 두 가설(B-1), nginx 공격적 튜닝 가설(B-3)을 변수 분리 측정으로 기각하고 실제 병목·개선 요인을 도출
- 재현성 확보 — 핵심 측정은 최소 2회 반복, 정확성 검증은 20회 반복

## AI 산출물에서 발견·수정한 사례

AI가 작성한 코드와 문서를 그대로 두지 않고 측정·코드 grep으로 확인한 결과 발견한 사례다.

### 1. HoldController 응답 코드 누락

부하 테스트 중 k6의 `201` 체크가 0건으로 잡혔는데, `http_req_failed` 역산 결과 비실패 응답이 존재했다. 컨트롤러를 확인하니 `ResponseEntity` 없이 `HoldResponse`만 반환하고 있었고, Spring이 기본 200 OK를 내고 있었다.

```java
@PostMapping
@ResponseStatus(HttpStatus.CREATED)  // 부하 테스트 중 발견 후 추가
public HoldResponse createHold(...) { ... }
```

응답 코드 분포까지 따로 집계하지 않았다면 발견하기 어려운 결함이었다.

### 2. 노션 본문 초안에서 측정 데이터와 어긋난 표현 다섯 건 수정

AI가 작성한 노션 트러블슈팅 본문 초안을 코드 grep과 측정 데이터로 대조해 다섯 건을 정정했다.

- **캐시 evict 지점 "4곳" → "6곳"** — 본문은 "이 네 지점"으로 적혀 있었으나 실제 코드는 6곳에서 호출되고 있었다(HoldService 생성·해제, HoldCleanupScheduler 만료 정리, ReservationConfirmedEventListener 확정, ReservationService 환불, SellerService 좌석 추가).
- **retry 표현 "최대 3회" → "최초 시도 + 재시도 2회, 총 3번"** — `queue-flow-with-retry.js`의 `MAX_RETRIES=2`는 재시도 횟수다. "최대 3회"는 재시도 3회로 오해될 수 있어 명확화했다.
- **Knee Point 시행착오 "3회차" → "4회차"** — 1회차(ramp 단계 진행 안 됨)를 누락한 압축본을 발견해 4회차로 복원했다.
- **Part B-1 끝에 VT ablation 미완성 한계 단락 추가** — 캐시 적용 후 환경은 `pool=30 + VT on` 한 조건만 측정했고 VT off 비교는 수행하지 않았다. "2.2배 RPS 증가에서 Virtual Thread 기여도는 본 데이터로 분리 불가"라는 한계를 본문에 명시했다.
- **Part A 끝에 단일 Redis 전제 한계 단락 추가** — Redisson 미도입은 단일 Redis 인스턴스 전제에서만 유효하다. Sentinel/Cluster 전환 시 라이브러리 선택을 재검토해야 한다는 단서를 본문에 명시했다.

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

# 트러블슈팅 — 데이터 정합성과 분산 환경

부하 테스트 영역과 별도로, 데이터 정합성·메시지 유실·멀티 인스턴스 환경에서 마주친 문제와 그에 대응한 결정을 정리했다. 4건 모두 코드 주석에 고민 흐름이 그대로 남아 있다.

<details>
<summary><b>1. Saga 보상 트랜잭션 — 결제는 커밋됐는데 예약이 실패하면 포인트는 어디로 가는가</b></summary>

### 문제 상황

결제 흐름은 3단계로 나뉘어 있다.

1. `approvePayment()` — 결제 승인. 포인트 차감 또는 PG 승인.
2. `completePayment()` — 예약 확정. DB에 Reservation 저장.
3. 이벤트 발행 — Kafka로 알림.

1단계는 외부 호출(PG) 또는 포인트 차감을 포함해 빠르게 커밋되어야 하므로 별도 트랜잭션에서 처리된다. 여기서 문제가 생긴다. **1단계는 이미 커밋됐는데 2단계에서 예외가 발생하면, `@Transactional` 롤백은 2단계만 되돌리고 1단계 포인트 차감은 되돌릴 수 없다.** 포인트는 빠졌는데 예약은 없는 상태가 된다.

### 결정 — REQUIRES_NEW로 분리한 보상 트랜잭션

같은 트랜잭션 안에서 보상 코드를 실행하면 outer 트랜잭션 롤백 시 보상까지 함께 롤백된다. 그러면 보상이 의미가 없다. `Propagation.REQUIRES_NEW`로 기존 트랜잭션과 완전히 독립된 새 트랜잭션을 시작해 보상 결과(포인트 환불 + 결제 CANCELED)가 outer 롤백과 무관하게 DB에 반영되도록 했다.

```java
// PaymentCompensationService.java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void compensateAfterReservationFailure(Long paymentId) {
    Payment payment = paymentRepository.findWithLockById(paymentId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));

    if (payment.getStatus() == PaymentStatus.CANCELED) {
        return;  // 멱등성 보장 — 중복 보상 방지
    }
    if (payment.getStatus() != PaymentStatus.APPROVED) {
        return;  // APPROVED가 아니면 보상 대상 아님
    }
    if (payment.getPaymentMethod() == PaymentMethod.POINT) {
        refundPoints(payment.getUserId(), payment.getAmount());
    }
    payment.setStatus(PaymentStatus.CANCELED);
}
```

### 고려한 두 가지 디테일

- **비관적 락(`findWithLockById`)** — 동시에 다른 트랜잭션이 같은 결제를 수정하지 못하게 막는다. 환불 도중 재시도 보상이 들어오면 잔액 계산이 깨질 수 있다.
- **멱등성** — 이미 CANCELED 상태면 그냥 반환한다. 보상 호출이 재시도로 두 번 들어와도 포인트가 두 번 환불되지 않는다.

### 인정한 한계

CARD 결제의 PG 취소 API 호출은 샌드박스 환경 한계로 미구현 상태이고, 현재는 DB 상태만 CANCELED로 변경한다. 실 운영 환경에서는 Toss 취소 API 연동이 추가되어야 한다.

</details>

<details>
<summary><b>2. JWT 즉시 무효화 — 멀티 인스턴스 환경에서 로그아웃은 어디에 기록해야 하는가</b></summary>

### 문제 상황

JWT는 기본적으로 stateless다. 서명만 맞으면 서버는 토큰을 신뢰한다. 그런데 로그아웃·비밀번호 변경 같은 상황에서는 **만료 전 토큰을 즉시 무효화**해야 한다. 앱 서버가 2대 이상이면 한 서버에 저장한 무효화 정보가 다른 서버에서는 보이지 않는다는 문제가 추가로 생긴다.

### 결정 — Access는 Redis, Refresh는 DB로 분리

토큰 종류에 따라 저장소를 다르게 선택했다.

| 토큰 | 저장소 | 키/필드 | 이유 |
|------|--------|--------|------|
| Access | Redis (`jwt:bl:{jti}`) | jti, TTL = 토큰 남은 유효 시간 | 검증이 요청마다 발생 → 빠른 조회 필요. TTL 자동 삭제로 메모리 정리 불필요 |
| Refresh | DB `refresh_tokens.revoked` | jti unique 인덱스 | 재발급 시에만 검증 → 빈도 낮음. 토큰 발급 이력 영구 추적 필요 |

Access는 검증이 모든 요청에서 일어나므로 Redis에 두어 멀티 인스턴스 간 즉시 공유되도록 했다. Refresh는 발급·재발급 이력을 운영 측면에서 추적할 필요가 있어 DB에 영속 저장했다.

```java
// TokenBlacklistService.java — Access jti 블랙리스트
public void blacklistAccessJti(String jti, Instant accessExpiresAt) {
    long seconds = Duration.between(Instant.now(), accessExpiresAt).getSeconds();
    if (seconds <= 0) return;  // 이미 만료된 토큰은 넣을 필요 없음
    redisTemplate.opsForValue().set(PREFIX + jti, "1", Duration.ofSeconds(seconds));
}
```

### 고려한 디테일

- **TTL을 남은 유효 시간으로 설정** — 토큰이 자연 만료되는 시점에 블랙리스트도 함께 사라진다. 별도 정리 스케줄러가 필요 없다.
- **이미 만료된 토큰은 블랙리스트에 넣지 않음** — `seconds <= 0` 가드. 만료된 토큰은 어차피 검증에서 거부되므로 메모리 낭비를 막는다.

</details>

<details>
<summary><b>3. Transactional Outbox — DB 커밋은 됐는데 Kafka 발행이 실패하면 이벤트는 어떻게 보장하나</b></summary>

### 문제 상황

예약 확정 트랜잭션 안에서 DB 저장과 Kafka 이벤트 발행을 동시에 수행해야 한다. 그런데 두 작업의 원자성을 보장할 수 없다.

- DB 커밋 성공 → Kafka 발행 실패: 예약은 확정됐는데 알림이 누락된다.
- Kafka 발행 성공 → DB 커밋 실패(거의 없지만 이론상): 예약이 없는데 알림이 나간다.

### 결정 — Outbox 테이블로 두 작업의 원자성을 DB 트랜잭션에 위임

같은 트랜잭션 안에서 `kafka_outbox` 테이블에 INSERT를 함께 실행한다. DB가 커밋되면 Outbox 행도 반드시 함께 저장된다. 별도 스케줄러(`KafkaOutboxPublishScheduler`)가 PENDING 행을 폴링해 Kafka에 발행하고, 성공 시 PUBLISHED로 갱신한다.

| 컬럼 | 역할 |
|------|------|
| `partition_key` | Kafka 파티션 키 (seatId). 같은 좌석 이벤트의 순서 보장 |
| `payload_json` | LONGTEXT로 이벤트 직렬화 |
| `status` | PENDING → PUBLISHED / FAILED |
| `publish_attempts` | 발행 시도 횟수. 최대치 초과 시 FAILED로 전환되어 알람 대상 |
| `last_error` | 마지막 실패 메시지 (length 1024) |

### 인정한 트레이드오프

- **중복 발행 가능성** — Kafka 발행 성공 후 status 업데이트 직전 장애 시 동일 메시지가 두 번 발행될 수 있다. **컨슈머 쪽 멱등성(paymentKey, idempotence=true) 처리가 전제 조건**이다.
- **발행 지연** — 스케줄러 폴링 주기만큼 실시간성이 떨어진다. 알림 수준의 비동기 작업에는 허용 가능하다고 판단했다.
- **테이블 적체** — `kafka_outbox`가 일시적으로 쌓일 수 있어 주기적 정리가 필요하다. 현재는 PUBLISHED 행의 별도 정리 배치는 미구현 상태다.

</details>

<details>
<summary><b>4. Resilience4j 서킷브레이커 — 어디까지 보호되고 어디는 보호되지 않는가</b></summary>

### 결정 — Redis 직접 호출 경로에만 CB 적용

`HoldStore`, `QueueService` 등 Redis를 직접 호출하는 코드에 Resilience4j `redisCircuitBreaker`를 적용했다. 각 호출 지점마다 try-catch를 반복 작성하지 않기 위해 `RedisCircuitBreakerExecutor`로 공통 패턴을 한 곳에 모았다.

```java
// 사용 예시
redisCb.execute(
    "hold.getHold",                    // 로그용 작업 이름
    () -> redisTemplate.get(tokenKey), // 실제 Redis 호출
    () -> null                         // CB OPEN 시 fallback
);
```

fallback 전략은 작업 성격에 따라 다르게 잡았다.

| 작업 | fallback | 이유 |
|------|---------|------|
| 홀드 생성 | 0L (실패) | 락 획득은 명시적 실패가 안전. 임의 성공으로 처리하면 안 됨 |
| 홀드 조회 | null | "홀드 없음"으로 폴백해 요청 자체는 완전 실패시키지 않음 |
| 좌석 홀드 여부 | null | 동일 |

### 인정한 한계 — `@Cacheable`은 CB 적용 범위 밖

잔여석 캐시(`@Cacheable`)는 Spring `RedisCacheManager`의 기본 동작 영역이라 위 `RedisCircuitBreakerExecutor`를 거치지 않는다. 즉 **Redis 장애 시 잔여석 캐시 호출은 CB 보호를 받지 못한다.** 이력서에는 "Resilience4j로 Redis 장애 격리"라고 한 줄 적었지만, 정확히는 직접 호출 경로 한정이다.

해결책은 두 방향이 있다. (1) `CacheManager`를 커스텀해 캐시 호출도 CB 안으로 묶거나, (2) Redis 캐시 실패 시 fallback으로 DB 직접 조회로 떨어지도록 명시적 처리. 현재 프로젝트에서는 잔여석 캐시 자체가 도메인상 1~2초 근사 허용이라 우선순위에서 밀려 미적용 상태로 두었고, 이 한계를 노션 본문에도 한계 항목으로 명시했다.

</details>

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
