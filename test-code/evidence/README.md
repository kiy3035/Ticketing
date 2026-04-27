# 테스트 실행 산출물 (Evidence)

> 실제 테스트를 실행한 결과 스크린샷·리포트를 모아 두는 폴더.
> 면접에서 코드와 함께 보여줄 수 있는 **증거 자료**.

## 캡처 가이드

캡처 절차와 무엇을 캡처할지는 [`../04-capturing-evidence.md`](../04-capturing-evidence.md) 참조.

## 산출물 인덱스 (캡처 후 작성)

| # | 파일 | 캡처 시점 | 무엇을 보여주는가 |
|---|------|----------|-------------------|
| 1 | `01-gradle-test-result.png` | YYYY-MM-DD | 전체 테스트 통과 (21개 메서드) + 소요 시간 |
| 2 | `02-html-report-summary.png` | YYYY-MM-DD | HTML 리포트 메인 — Successful/Failed/Skipped |
| 3 | `03-html-report-detail.png` | YYYY-MM-DD | 패키지별 트리 (concurrency / hold / queue / lock) |
| 4 | `04-concurrency-result.png` | YYYY-MM-DD | 100 threads → 1 success (핵심 산출물) |
| 5 | `05-jacoco-coverage.png` | YYYY-MM-DD | JaCoCo 커버리지 (hold/lock/queue 60%+) |
| 6 | `06-archunit-pass.png` | YYYY-MM-DD | ArchUnit 3개 규칙 모두 통과 |

## 캡처 명령 빠른 참조

```bash
# 1. 전체 테스트 + 리포트
./gradlew clean test
start build/reports/tests/test/index.html

# 2. 동시성 테스트 따로 (IntelliJ에서 실행하는 것이 캡처하기 좋음)
./gradlew test --tests "*ConcurrencyTest"

# 3. 커버리지 (JaCoCo 도입 후)
./gradlew test jacocoTestReport
start build/reports/jacoco/test/html/index.html
```

## 주의사항

- 모든 캡처는 **빌드 통과 상태**에서 진행
- 개인정보·서버 IP·이메일이 보이면 모자이크
- 다크 모드보다 **라이트 모드** 권장 (가독성)
