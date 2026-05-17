# 테스트 실행 산출물 (Evidence)

> `./gradlew test` 를 실제로 실행한 결과를 기록한 파일.
> 면접에서 코드와 함께 보여줄 수 있는 증거 자료.

## 실행 결과 요약 (최신)

| 항목 | 결과 |
|------|------|
| 최종 갱신일 | 2026-05-12 (SSE 다중 인스턴스 4 시나리오 추가) |
| 전체 테스트 수 | **46개** (카탈로그 합계) |
| 통과 | **46개** |
| 실패 | **0개** |
| 성공률 | **100%** |
| 소요 시간 | 약 77초~1분대 (Testcontainers 이미지 캐시 후) |
| 빌드 결과 | **BUILD SUCCESSFUL** |

> 아래 `Test-Summary-Final.png`(48 tests 캡처)는 2026-05-03 시점 스냅샷이며, 이후 SSE 4개·기타 추가로 카탈로그 기준 46개로 정리됨. 캡처 시점과 카탈로그 기준이 다른 이유는 일부 옛 테스트가 통합/제거되고 새 시나리오가 추가되었기 때문 — 단일 진실의 원천(SoT)은 [`test-code/05-test-catalog.md`](../05-test-catalog.md).

## 산출물 (../images/)

| 파일 | 위치 | 캡처 내용 | 면접 활용 포인트 |
|------|------|---------|------------------|
| `Test-Summary-Final.png` | `test-code/images/` | HTML 리포트 — **48 tests / 0 failures / 100% successful** + 패키지별 분류 14개 | "단위·슬라이스·통합·동시성·아키텍처 5종, 14개 패키지에 걸쳐 48개 케이스 100% 통과" |
| `SeatHoldConcurrencyTest.png` | `test-code/images/` | 콘솔 — **`100명이 동시에 같은 좌석 홀드 시도 → 정확히 1명만 성공 PASSED`** + ArchUnit 3개 규칙 통과 | "분산 락의 정확성을 자동화 테스트로 증명. ArchUnit으로 레이어 의존성도 CI에서 강제" |
| `jwt 테스트 결과.png` | `test-code/images/` | 콘솔 — **JWT 인증 통합 테스트 8개 PASSED** (로그아웃 블랙리스트·DB revoke·Case2 자동 재발급·sub 불일치·TTL 만료·서명 위조·둘 다 만료·형식 깨짐) | "JWT stateless 약점 보완을 실제 MySQL+Redis Testcontainers 환경에서 자동화 검증" |
| `sse 다중인스턴스 테스트 결과.png` | `test-code/images/` | 콘솔 — **SSE 다중 인스턴스 통합 테스트 4개 PASSED** (cross-instance broadcast·양쪽 동시 연결·사용자 격리·no-op) | "스케일아웃 환경의 SSE 알림 누락 문제를 Redis Pub/Sub 으로 해결, 발행/구독 인스턴스가 달라도 정상 전달됨을 자동화 검증" |

## 추가 실행 결과 (2026-05-03)

| 테스트 클래스 | 결과 파일 | 통과 | 소요 시간 |
|--------------|-----------|------|-----------|
| `JwtAuthenticationIntegrationTest` | [jwt-auth-test-result.md](jwt-auth-test-result.md) | 8/8 | - |
| `IdempotencyServiceTest` | [idempotency-test-result.md](idempotency-test-result.md) | 3/3 | - |
| 위 두 클래스 합산 | | **11/11** | **1분 10초** |
| `RedisCircuitBreakerExecutorTest` | [circuit-breaker-test-result.md](circuit-breaker-test-result.md) | 3/3 | 13초 |
| `RedisCircuitBreakerIntegrationTest` | [circuit-breaker-test-result.md](circuit-breaker-test-result.md) | 3/3 | 57초 |
| `SseNotificationMultiInstanceIntegrationTest` | [sse-multi-instance-test-result.md](sse-multi-instance-test-result.md) | 4/4 | 1분 9초 |

## 테스트 구성 (46개 메서드 — 카탈로그 기준)

> 정확한 분류·메서드별 시나리오는 [`05-test-catalog.md`](../05-test-catalog.md) 참고. 아래는 영역별 요약.

| 영역 | 종류 | 비고 |
|------|------|------|
| 단위 테스트 (Unit, Mockito) | 4 클래스 / 15 메서드 | RedisLock, Queue, Hold, Idempotency 등 |
| 슬라이스 (Web Slice, @WebMvcTest) | 2 클래스 / 2 메서드 | HoldController, QueueController |
| 통합 테스트 (Testcontainers) | 7 클래스 / 23 메서드 | **JWT 8 / SSE 다중 인스턴스 4 / Saga 4 / Kafka 멱등성 4 / 서킷브레이커 / 멱등 / Rate Limit 등** |
| 동시성 (CountDownLatch + ExecutorService) | 2 클래스 / 2 메서드 | RedisLockConcurrency, SeatHoldConcurrency |
| 아키텍처 (ArchUnit) | 1 클래스 / 3 메서드 | 레이어 의존성 강제 |
| 컨텍스트 로딩 (Smoke) | 1 클래스 / 1 메서드 | 부팅 검증 |
| **합계** | **17 클래스 / 46 메서드** | — |

## 핵심 검증 시나리오

### 동시성 테스트 (가장 중요)
- **100명이 동시에 같은 좌석 홀드 시도 → 정확히 1명만 성공** (`SeatHoldConcurrencyTest`)
- **50개 스레드가 동시에 동일 키 락 시도 → 1개만 획득** (`RedisLockConcurrencyTest`)
- Redis 분산 락(SET NX) + Lua 스크립트 원자성 보장 검증
- `CountDownLatch` 2단계 게이트로 진짜 동시 출발 보장

### 아키텍처 규칙 검증
- Controller → Repository 직접 의존 금지
- Domain → Spring 프레임워크 의존 금지
- Service → Controller 역방향 의존 금지

## 재현 방법

```bash
# 전체 테스트 실행
./gradlew test

# 특정 테스트만 (한글 깨짐 방지: PowerShell UTF-8 설정 후)
$OutputEncoding = [Console]::OutputEncoding = [Text.Encoding]::UTF8
./gradlew test --tests "*.SeatHoldConcurrencyTest" --rerun-tasks

# HTML 리포트 보기
start build/reports/tests/test/index.html
```
