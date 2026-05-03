# 05. 테스트 카탈로그

> 현재 프로젝트에 작성된 **모든 테스트 클래스** 와 그 안의 **테스트 메서드별 시나리오** 를 표로 정리한다.
> 면접관이 "어떤 케이스들을 검증했나요?" 라고 물으면 이 문서를 보면 된다.

## 요약

| 종류 | 클래스 수 | 메서드 수 | 비고 |
|------|----------|----------|------|
| 단위 테스트 (Unit) | 4 | 15 | Mockito |
| 슬라이스 테스트 (Web Slice) | 2 | 2 | @WebMvcTest |
| 통합 테스트 (Integration) | 6 | 19 | Testcontainers |
| 동시성 테스트 (Concurrency) | 2 | 2 | CountDownLatch + ExecutorService |
| 아키텍처 테스트 (ArchUnit) | 1 | 3 | @ArchTest |
| 컨텍스트 로딩 (Smoke) | 1 | 1 | 부팅 검증 |
| **합계** | **16** | **42** | JWT 블랙리스트 8 + 서킷브레이커 6 + 멱등성 race condition 포함 |

---

## 1. 단위 테스트 (Unit)

### 1-1. `RedisLockServiceTest`
**검증 대상**: Redis 기반 분산 락의 획득(`tryLock`)·해제(`unlock`)
**의존성 처리**: `StringRedisTemplate`, `ValueOperations` 모두 Mock

| # | 메서드 | 시나리오 | 기대 결과 |
|---|--------|----------|-----------|
| 1 | `tryLock_returnsToken_whenSetIfAbsentSucceeds` | Redis SET NX 성공 | UUID 토큰 반환 |
| 2 | `tryLock_returnsEmpty_whenSetIfAbsentFails` | 다른 클라이언트가 선점 중 | `Optional.empty()` |
| 3 | `unlock_returnsTrue_whenTokenMatches` | Lua 스크립트로 토큰 일치 확인 → DEL 성공 | `true` |
| 4 | `unlock_returnsFalse_whenTokenDoesNotMatch` | 다른 사람의 토큰으로 unlock 시도 | `false` (안전장치) |

### 1-2. `QueueServiceTest`
**검증 대상**: Redis ZSet 기반 대기열 진입·순번·인원수
**의존성 처리**: `StringRedisTemplate`, `ZSetOperations` Mock

| # | 메서드 | 시나리오 | 기대 결과 |
|---|--------|----------|-----------|
| 1 | `enterQueue_returnsTokenWithRankAndTotalWaiting` | 대기열 진입 (첫 번째 사용자) | rank=1, totalWaiting=1, 토큰 발급 |
| 2 | `getRank_returnsOneBasedRank` | ZSet rank=2(0-based) → +1 변환 | rank=3 (1-based) |
| 3 | `getRank_returnsNull_whenTokenNotInQueue` | 토큰 만료/잘못된 토큰 | `null` |
| 4 | `countWaiting_returnsZero_whenQueueEmpty` | 대기열 빈 상태 | `0` |
| 5 | `countWaiting_returnsSize_whenQueueHasMembers` | 100명 대기 중 | `100` |

### 1-3. `HoldServiceTest`
**검증 대상**: 좌석 홀드 생성의 비즈니스 분기
**의존성 처리**: 7개 의존성 모두 Mock

| # | 메서드 | 시나리오 | 기대 결과 |
|---|--------|----------|-----------|
| 1 | `createHold_throws429_whenLockFails` | 다른 사용자가 선점 중 (락 실패) | `ResponseStatusException(429)` "Seat is busy" |
| 2 | `createHold_throws404_whenSeatNotFound` | seatId가 DB에 없음 | `ResponseStatusException(404)` |
| 3 | `createHold_returnsHoldResponse_whenLockSucceedsAndHoldCreated` | 정상 흐름 | HoldResponse 반환 + 락 해제 verify |

---

## 2. 슬라이스 테스트 (Web Slice)

### 2-1. `HoldControllerIntegrationTest` (`@WebMvcTest(HoldController.class)`)
**검증 대상**: `POST /api/holds` HTTP 계약

| # | 메서드 | 시나리오 | 기대 결과 |
|---|--------|----------|-----------|
| 1 | `createHold_returns200WithHoldToken_whenSuccess` | 인증된 사용자가 정상 요청 | HTTP 200 + body에 `holdToken` 포함 |

### 2-2. `QueueControllerIntegrationTest` (`@WebMvcTest(QueueController.class)`)
**검증 대상**: `POST /api/queue/enter` HTTP 계약

| # | 메서드 | 시나리오 | 기대 결과 |
|---|--------|----------|-----------|
| 1 | `enter_returns201WithTokenAndRank_whenQueueEnterSucceeds` | 대기열 진입 성공 | HTTP 201 + `data.token`, `data.rank`, `data.totalWaiting` |

---

## 3. 통합 테스트 (Integration)

### 3-1. `IdempotencyServiceTest` (Testcontainers Redis)

| # | 메서드 | 시나리오 | 기대 결과 |
|---|--------|----------|-----------|
| 1 | `acquireAndRetrieve` | 키 선점 → 결과 저장 → 동일 키 재요청 | 중복 선점 불가, 결과 캐시 반환 |
| 2 | `releaseOnFailure` | 처리 실패 시 키 해제 → 재시도 | 재선점 성공 |

### 3-2. `RateLimitServiceTest` (Testcontainers Redis Sliding Window)

| # | 메서드 | 시나리오 | 기대 결과 |
|---|--------|----------|-----------|
| 1 | `rateLimitExceeded` | 1초 내 3회 초과 호출 | 4번째 요청 거부 |

### 3-3. `TicketingApplicationTests` (Smoke)

| # | 메서드 | 시나리오 | 기대 결과 |
|---|--------|----------|-----------|
| 1 | `contextLoads` | Spring Boot 컨텍스트 로딩 | 모든 빈 정상 주입 (부팅 가능) |

### 3-4. `JwtAuthenticationIntegrationTest` (Testcontainers MySQL + Redis) ⭐
**검증 대상**: JWT stateless 약점 보완 — Redis 블랙리스트 + DB Refresh revoke + 4-case 자동 재발급
**의존성**: 실제 MySQL(Flyway 없이 create-drop) + 실제 Redis + `TestRestTemplate` 전체 HTTP 호출

| # | 메서드 | 시나리오 | 기대 결과 |
|---|--------|----------|-----------|
| 1 | `logout_blacklistsAccessJti_andRejectsSubsequentRequests` | 로그아웃 → 같은 Access 로 보호 API 호출 | Redis `jwt:bl:{jti}` 키 존재 + 401 |
| 2 | `logout_revokesRefresh_andBlocksAccessReissue` | 로그아웃 후 만료 Access + revoke 된 Refresh 로 재발급 시도 | DB `revoked=true` 로 차단 → 401 |
| 3 | `case2_reissuesAccess_viaResponseHeader` | 만료 Access + 유효 Refresh 로 호출 (Case 2) | 200 OK + `X-New-Access-Token` 헤더에 유효 JWT |
| 4 | `mismatchedSubject_isRejected` | A의 Access + B의 Refresh (subject 불일치) | 토큰 짜깁기 차단 → 401 |
| 5 | `blacklistKey_expiresAutomatically_byAccessRemainingTtl` | 짧은 TTL Access jti 블랙리스트 등록 → TTL 경과 | Redis 키 자동 삭제 (메모리 누수 방지) |

### 3-5. `RedisCircuitBreakerExecutorTest` (단위) ⭐
**검증 대상**: `RedisCircuitBreakerExecutor` CLOSED/OPEN/예외 분기 — Mockito로 CircuitBreaker 목
**상세**: [15-resilience-circuit-breaker-test.md](15-resilience-circuit-breaker-test.md)

| # | 메서드 | 시나리오 | 기대 결과 |
|---|--------|----------|-----------|
| 1 | `execute_returnsActionResult_whenCircuitClosed` | 회로 CLOSED, Redis 호출 성공 | action 결과값 반환 |
| 2 | `execute_returnsFallback_whenCircuitOpen` | 회로 OPEN — `CallNotPermittedException` | fallback 반환 |
| 3 | `execute_returnsFallback_andRecordsFailure_whenRedisThrows` | Redis 예외 발생 | fallback 반환 |

### 3-6. `RedisCircuitBreakerIntegrationTest` (Testcontainers Redis) ⭐
**검증 대상**: CLOSED → OPEN → HALF_OPEN → CLOSED 전체 상태 전이 + QueueService 레벨 fallback
**상세**: [15-resilience-circuit-breaker-test.md](15-resilience-circuit-breaker-test.md)

| # | 메서드 | 시나리오 | 기대 결과 |
|---|--------|----------|-----------|
| 1 | `circuitBreaker_open_returnsFallback_withoutCallingRedis` | OPEN 강제 전환 → execute() 호출 | fallback 반환, action 람다 실행 횟수 0 |
| 2 | `circuitBreaker_transitionsToOpen_afterFailureRateExceeded` | 슬라이딩 윈도우 실패율 초과 | 상태 OPEN 전환 확인 |
| 3 | `circuitBreaker_halfOpen_closesAfterSuccessfulProbes` | HALF_OPEN → 3회 성공 프로브 | 상태 CLOSED 복귀 |
| 4 | `queueService_enterQueue_returnsFallback_whenCircuitForcedOpen` | OPEN 상태에서 QueueService 호출 | 예외 없이 fallback 처리 |

---

## 4. 동시성 테스트 (Concurrency) ⭐ 핵심

### 4-1. `RedisLockConcurrencyTest`
**검증 대상**: Redis SET NX 의 race condition 안전성

| # | 메서드 | 시나리오 | 기대 결과 |
|---|--------|----------|-----------|
| 1 | `concurrentLock_onlyOneAcquires` | 50개 스레드가 동시에 동일 키 락 시도 | **정확히 1개만 토큰 획득** |

### 4-2. `SeatHoldConcurrencyTest` ⭐⭐⭐
**검증 대상**: 좌석 동시 선점 차단 (분산 락 + Lua 스크립트 원자성)

| # | 메서드 | 시나리오 | 기대 결과 |
|---|--------|----------|-----------|
| 1 | `concurrentHold_onlyOneSucceeds` | 100명이 동시에 같은 좌석에 홀드 시도 | **정확히 1명만 성공, 99명 실패** |

> 💡 이 테스트는 프로젝트의 **핵심 비즈니스 요구사항**("좌석 중복 선점 절대 금지")을 자동화로 증명한다.
> 면접에서 가장 강조해야 할 산출물.

---

## 5. 아키텍처 테스트 (ArchUnit)

### 5-1. `ArchitectureTest`
**검증 대상**: 패키지 의존성 규칙

| # | 규칙 | 의도 |
|---|------|------|
| 1 | `controllersDoNotTouchRepositories` | Controller → Repository 직접 의존 금지 (Service 레이어 강제) |
| 2 | `domainIndependentOfSpring` | Domain → Spring 의존 금지 (POJO 도메인) |
| 3 | `servicesDoNotDependOnControllers` | Service → Controller 의존 금지 (역방향 차단) |

---

## 6. 누락된(=향후 추가하면 좋을) 테스트

> 면접에서 "추가로 작성하고 싶은 테스트가 있나요?" 라는 질문 대비

| 영역 | 시나리오 | 우선순위 |
|------|----------|---------|
| 인증 | "JWT 서명 위조·만료 토큰 거부" 추가 엣지 케이스 | ✅ 완료 → `JwtAuthenticationIntegrationTest` 시나리오 6·7·8 |

> 아래 항목들은 완료됨 — 향후 추가 필요 없음

| 영역 | 완료된 테스트 | 위치 |
|------|-------------|------|
| 결제 Saga | 결제 실패 시 포인트 복원·CANCELED 전환·REQUIRES_NEW·멱등성 4 시나리오 | `PaymentCompensationIntegrationTest` |
| Kafka 컨슈머 재시도 | 예외 → 멱등성 키 해제 → Kafka 재전송 시 재처리 가능 | `PaymentCompleteEventConsumerIntegrationTest` 시나리오 4, `SeatHoldEventConsumerIntegrationTest` 시나리오 4 |
| 서킷브레이커 | CLOSED/OPEN/HALF_OPEN 전이 + fast-fail | `RedisCircuitBreakerExecutorTest`, `RedisCircuitBreakerIntegrationTest` |
| 멱등성 race condition | 50개 스레드 동시 선점 → 정확히 1개만 성공 | `IdempotencyServiceTest.concurrentAcquire_onlyOneSucceeds` |

---

## 7. 테스트 작성 원칙 (이 카탈로그를 늘릴 때)

1. **분기마다 테스트** — 200/4xx/5xx 응답이 갈리는 분기에 각각 테스트가 있어야 함
2. **이름이 시나리오를 말하게** — `메서드_조건_기대결과` 형식
3. **하나의 테스트 = 하나의 시나리오** — 한 테스트에서 여러 가지 검증하지 않기
4. **느린 테스트는 격리** — 통합·동시성 테스트는 별도 패키지·태그로 분리
5. **flaky test 금지** — `Thread.sleep` 대신 `CountDownLatch`, `Awaitility` 사용
