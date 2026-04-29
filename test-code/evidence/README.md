# 테스트 실행 산출물 (Evidence)

> `./gradlew test` 를 실제로 실행한 결과를 기록한 파일.
> 면접에서 코드와 함께 보여줄 수 있는 증거 자료.

## 실행 결과 요약

| 항목 | 결과 |
|------|------|
| 실행일 | 2026-04-27 |
| 전체 테스트 수 | **48개** |
| 통과 | **48개** |
| 실패 | **0개** |
| 성공률 | **100%** |
| 소요 시간 | 약 9초 (Testcontainers 이미지 캐시 후) |
| 빌드 결과 | **BUILD SUCCESSFUL** |

## 산출물 (../images/)

| 파일 | 위치 | 캡처 내용 | 면접 활용 포인트 |
|------|------|---------|------------------|
| `Test-Summary-Final.png` | `test-code/images/` | HTML 리포트 — **48 tests / 0 failures / 100% successful** + 패키지별 분류 14개 | "단위·슬라이스·통합·동시성·아키텍처 5종, 14개 패키지에 걸쳐 48개 케이스 100% 통과" |
| `SeatHoldConcurrencyTest.png` | `test-code/images/` | 콘솔 — **`100명이 동시에 같은 좌석 홀드 시도 → 정확히 1명만 성공 PASSED`** + ArchUnit 3개 규칙 통과 | "분산 락의 정확성을 자동화 테스트로 증명. ArchUnit으로 레이어 의존성도 CI에서 강제" |

## 테스트 구성 (31개 메서드)

| 패키지 | 종류 | 테스트 수 |
|--------|------|----------|
| `concurrency` | 동시성 (Testcontainers) | 2 |
| `architecture` | ArchUnit | 3 |
| `hold.controller` | 슬라이스 (@WebMvcTest) | 1 |
| `hold.service` | 단위 (Mockito) | 3 |
| `idempotency` | 통합 (Testcontainers) | 2 |
| `lock` | 단위 (Mockito) | 4 |
| `payment.event` | **Kafka 멱등성 통합 (Testcontainers)** | **4** |
| `payment.service` | **Saga 보상 통합 (Testcontainers)** | **4** |
| `queue.controller` | 슬라이스 (@WebMvcTest) | 1 |
| `queue.service` | 단위 (Mockito) | 5 |
| `ratelimit` | 통합 (Testcontainers) | 1 |
| `(root)` | 컨텍스트 로딩 | 1 |
| **합계** | | **31** |

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
