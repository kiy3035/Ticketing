# 테스트 코드 가이드 (test-code)

> **이 디렉토리는 무엇인가?**
> 이 프로젝트의 테스트 전략·작성 컨벤션·실행 방법·결과 캡처 방식을 한 곳에 정리한 문서다.
> 면접에서 "테스트 코드 작성해 보셨나요?" 라는 질문에 **근거 자료(코드 + 리포트 + 스크린샷)** 로 답할 수 있도록
> 문서와 산출물을 함께 묶어 둔다.

## 문서 목차

| 문서 | 내용 |
|------|------|
| [01-test-strategy.md](01-test-strategy.md) | **테스트 피라미드** — 무엇을 어디서 검증하고, 왜 그렇게 나누는가 |
| [02-test-types-and-conventions.md](02-test-types-and-conventions.md) | **4가지 테스트 유형** (단위/슬라이스/통합/동시성) + Given-When-Then 컨벤션 |
| [03-how-to-run.md](03-how-to-run.md) | **실행 명령** — Gradle 태스크, IntelliJ, CI |
| [04-capturing-evidence.md](04-capturing-evidence.md) | **결과 캡처** — HTML 리포트, JaCoCo 커버리지, 스크린샷 폴더 운영 |
| [05-test-catalog.md](05-test-catalog.md) | **테스트 카탈로그** — 현재 작성된 12개 테스트의 시나리오/검증 항목 표 |
| [06-interview-qa.md](06-interview-qa.md) | **면접 예상 Q&A** — 테스트 관련 질문에 대한 답변 스크립트 |
| [07-bugs-found-via-testing.md](07-bugs-found-via-testing.md) | **실제 발견·수정한 버그 3건** — 테스트/코드리뷰로 찾은 근거 자료 |
| [08-k6-vs-junit.md](08-k6-vs-junit.md) | **k6 부하 테스트 vs JUnit** — 차이·역할 분담·포트폴리오 노출 가이드 |
| [09-saga-compensation-test.md](09-saga-compensation-test.md) | **Saga 보상 트랜잭션 통합 테스트** — 결제 실패 시 포인트 복원·멱등성 검증 4 시나리오 |
| [10-kafka-idempotency-test.md](10-kafka-idempotency-test.md) | **Kafka 컨슈머 멱등성 통합 테스트** — at-least-once 중복 처리 차단 검증 4 시나리오 |
| [11-seat-hold-consumer-idempotency-test.md](11-seat-hold-consumer-idempotency-test.md) | **SeatHoldEventConsumer 멱등성** — 동일 결함 연쇄 발견·holdToken+type 키 설계 |

## 산출물 (evidence/)

```
test-code/
├── 01-test-strategy.md
├── 02-test-types-and-conventions.md
├── 03-how-to-run.md
├── 04-capturing-evidence.md
├── 05-test-catalog.md
├── 06-interview-qa.md
└── evidence/                  ← 실제 실행 결과(스크린샷, 리포트 발췌)를 모아 둘 폴더
    ├── unit-tests-passed.png  (gradle test 결과)
    ├── coverage-report.png    (JaCoCo HTML 리포트)
    ├── concurrency-test.png   (100 threads → 1 success)
    └── ...
```

## 빠른 시작

```bash
# 1. 단위 테스트만 실행 (빠름, Docker 불필요)
./gradlew test --tests "*ServiceTest" --tests "*Test" -x integrationTest

# 2. 전체 테스트 (Docker 필요 — Testcontainers가 MySQL/Redis/Kafka 자동 기동)
./gradlew test

# 3. HTML 리포트 열기
# Windows
start build/reports/tests/test/index.html
```

## 핵심 메시지 (면접용 한 줄 요약)

> "단위 테스트로 비즈니스 로직을 빠르게 검증하고, **Testcontainers 기반 통합 테스트**로 실제 Redis 분산 락의 동시성 정확성을 검증합니다.
> ArchUnit으로 레이어 간 의존성 규칙을 CI에서 강제하고, 100개 스레드의 동시 좌석 선점 시나리오에서 **정확히 1개만 성공**함을 자동화 테스트로 증명합니다."
