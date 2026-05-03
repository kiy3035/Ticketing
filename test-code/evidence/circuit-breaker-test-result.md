# Resilience4j 서킷브레이커 테스트 — 실행 결과

> 실행일: 2026-05-03
> 환경: Windows 11 / PowerShell (UTF-8) / Docker Desktop (Testcontainers 자동 기동)

---

## 단위 테스트 결과

명령어: `./gradlew test --tests "RedisCircuitBreakerExecutorTest" --rerun-tasks`

| 항목 | 결과 |
|------|------|
| 테스트 수 | **3개** |
| 통과 | **3개** |
| 실패 | **0개** |
| 소요 시간 | **13초** (Docker 불필요) |
| 빌드 결과 | **BUILD SUCCESSFUL** |

```
RedisCircuitBreakerExecutorTest > Redis 예외 발생 — 실패 기록 후 fallback 반환 PASSED
RedisCircuitBreakerExecutorTest > 회로 OPEN — CallNotPermittedException → fallback 반환 PASSED
RedisCircuitBreakerExecutorTest > 회로 CLOSED — action 결과 그대로 반환 PASSED

BUILD SUCCESSFUL in 13s
```

---

## 통합 테스트 결과

명령어: `./gradlew test --tests "RedisCircuitBreakerIntegrationTest" --rerun-tasks`

![서킷브레이커 테스트 결과](../images/서킷브레이커%20test%20결과.png)

| 항목 | 결과 |
|------|------|
| 테스트 수 | **3개** |
| 통과 | **3개** |
| 실패 | **0개** |
| 테스트 실행 시간 | **1.300s** (Testcontainers 기동 포함 전체: 57초) |
| 빌드 결과 | **BUILD SUCCESSFUL** |

```
RedisCircuitBreakerIntegrationTest > HALF_OPEN → 프로브 3회 성공 → CLOSED 복귀 PASSED
RedisCircuitBreakerIntegrationTest > 슬라이딩 윈도우 실패율 초과 → OPEN 전환 PASSED
RedisCircuitBreakerIntegrationTest > OPEN 강제 전환 → action 미실행(fast-fail), fallback 반환 PASSED

BUILD SUCCESSFUL in 57s
```

---

## 검증 내용 상세

### 단위 테스트 — RedisCircuitBreakerExecutor 3개 분기

| # | 시나리오 | 핵심 assertion |
|---|----------|---------------|
| 1 | 회로 CLOSED — Redis 정상 응답 | action 결과값 그대로 반환 |
| 2 | 회로 OPEN — `CallNotPermittedException` | fallback 반환 (Redis 미호출) |
| 3 | Redis 예외 발생 | fallback 반환 (앱 미중단) |

> Docker 없이 13초 — execute() 메서드의 분기를 빠르게 커버.

### 통합 테스트 — 실제 운영 설정 기반 상태 전이

| # | 시나리오 | 핵심 assertion |
|---|----------|---------------|
| 1 | OPEN 강제 전환 → fast-fail | `actionCallCount == 0` (Redis 아예 안 부름) |
| 2 | 실패율 60% 초과 → OPEN 전환 | `getState() == OPEN` |
| 3 | HALF_OPEN → 프로브 3회 성공 → CLOSED | `getState() == CLOSED` |

> `application.properties` 운영 설정(sliding-window-size=10, failure-rate=50%, half-open=3) 그대로 적용.
> `transitionToOpenState()` 등 Resilience4j 공식 API로 상태 제어 — Redis 실제 다운 불필요.

---

## 인프라 구성 (Testcontainers 자동 기동)

| 컨테이너 | 이미지 | 용도 |
|----------|--------|------|
| MySQL | `mysql:8.0` | Spring Context 로딩 (이 테스트에서 직접 사용 안 함) |
| Redis | `redis:7-alpine` | Spring Context 로딩 (이 테스트에서 직접 사용 안 함) |
| Kafka | `confluentinc/cp-kafka:7.5.0` | Spring Context 로딩 (이 테스트에서 직접 사용 안 함) |

> 통합 테스트는 Spring Context 전체를 올리므로 컨테이너가 기동됨.
> 서킷브레이커 상태 제어는 Redis 실제 연결과 무관하게 `CircuitBreaker` 빈 직접 조작.

---

## 면접 활용 포인트

> "Redis가 죽어도 앱 전체가 타임아웃으로 멈추지 않도록 Resilience4j 서킷브레이커를 적용했습니다.
> OPEN 상태에서 action 람다 실행 횟수가 0임을 assertion으로 증명해 fast-fail이 실제로 동작함을 검증했고,
> CLOSED → OPEN → HALF_OPEN → CLOSED 전이 사이클 전체를 통합 테스트로 커버했습니다."
