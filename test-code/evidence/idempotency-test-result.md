# 멱등성 서비스 테스트 — 실행 결과

> 실행일: 2026-05-03
> 환경: Windows 11 / PowerShell (UTF-8) / Docker Desktop (Testcontainers 자동 기동)
> 명령어: `./gradlew test --tests "JwtAuthenticationIntegrationTest" --tests "IdempotencyServiceTest" --rerun-tasks`

---

## 결과 요약

| 항목 | 결과 |
|------|------|
| 테스트 수 | **3개** |
| 통과 | **3개** |
| 실패 | **0개** |
| 빌드 결과 | **BUILD SUCCESSFUL in 1m 10s** |

```
IdempotencyServiceTest > 멱등성 키 선점 → 결과 저장 → 동일 키 재요청 시 캐시 반환 PASSED
IdempotencyServiceTest > 처리 실패 시 키 해제 → 재시도 가능 PASSED
IdempotencyServiceTest > 50개 스레드 동시 선점 시도 → 정확히 1개만 성공 (Redis SET NX 원자성) PASSED

BUILD SUCCESSFUL in 1m 10s
5 actionable tasks: 5 executed
```

---

## 테스트별 검증 내용

| # | 시나리오 | 핵심 assertion |
|---|----------|---------------|
| 1 | 멱등성 키 선점 → 결과 저장 → 동일 키 재요청 캐시 반환 | 중복 선점 `false`, 결과 캐시 조회 `isPresent()` |
| 2 | 처리 실패 시 키 해제 → 재시도 가능 | `releaseKey` 후 재선점 `true` |
| 3 | **50개 스레드 동시 선점 → 정확히 1개만 성공** | `successCount == 1` (Redis SET NX 원자성) |

---

## 시나리오 3 상세 — race condition

```java
// 50개 스레드가 CountDownLatch로 동시에 출발
// Redis SET NX 원자성으로 단 1개만 acquireKey() true 반환
assertThat(successCount.get()).isEqualTo(1);
```

- `CountDownLatch(1)`로 50개 스레드를 동시에 출발시켜 진짜 race condition 재현
- Redis `SET NX`(SET if Not eXists)의 원자성으로 중복 선점 방지 검증
- 결제 API 동시 요청 시 이중 결제가 발생하지 않음을 자동화로 증명

---

## 면접 활용 포인트

> "결제 API에 동일한 요청이 동시에 들어오더라도 Redis SET NX 원자성으로
> 정확히 한 번만 처리됨을 50개 스레드 동시성 테스트로 검증했습니다.
> Kafka at-least-once 재전송 상황에서도 멱등성 키가 살아있으면 스킵,
> 처리 중 예외 시 키가 해제돼 Kafka 재시도 시 재처리가 가능합니다."
