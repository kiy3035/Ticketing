# 04. 결과 캡처 가이드 (면접 산출물)

> **목표**: 면접관이 코드를 못 봐도, **이 디렉토리의 스크린샷과 리포트만으로** "이 사람은 테스트 코드를 실무 수준으로 쓴다"고 판단할 수 있게 한다.

## 1. 캡처해야 할 6가지 산출물

| # | 산출물 | 출처 | 파일명(권장) |
|---|--------|------|------------|
| 1 | 전체 테스트 실행 결과 (Pass/Fail 요약) | Gradle 콘솔 | `01-gradle-test-result.png` |
| 2 | HTML 리포트 메인 화면 | `build/reports/tests/test/index.html` | `02-html-report-summary.png` |
| 3 | 패키지별 테스트 결과 트리 | HTML 리포트 | `03-html-report-detail.png` |
| 4 | 동시성 테스트 결과 (100 threads → 1 success) | IntelliJ 또는 콘솔 | `04-concurrency-result.png` |
| 5 | 커버리지 리포트 (JaCoCo) | `build/reports/jacoco/test/html/index.html` | `05-jacoco-coverage.png` |
| 6 | ArchUnit 결과 (의존성 규칙 통과) | IntelliJ 실행 결과 | `06-archunit-pass.png` |

## 2. 캡처 절차 (권장 워크플로우)

### Step 1. Docker 실행 확인
```bash
docker ps
```

### Step 2. 클린 빌드 후 전체 테스트
```bash
./gradlew clean test
```
→ **Step 1 산출물**: 콘솔 마지막 부분 (`BUILD SUCCESSFUL` + 테스트 요약) 캡처

### Step 3. HTML 리포트 열기
```bash
start build/reports/tests/test/index.html
```
→ **Step 2 산출물**: 메인 페이지 (성공 X개, 실패 0개, 소요 시간) 캡처
→ **Step 3 산출물**: "Packages" 탭에서 패키지별 트리 펼친 상태 캡처

### Step 4. 동시성 테스트 따로 강조
IntelliJ에서 `SeatHoldConcurrencyTest`를 직접 실행 → 통과 화면 캡처
→ **Step 4 산출물**: 테스트 메서드 옆 초록 체크 + DisplayName(`100명이 동시에...`) 보이도록

### Step 5. 커버리지 (JaCoCo 도입 후)
```bash
./gradlew test jacocoTestReport
start build/reports/jacoco/test/html/index.html
```
→ **Step 5 산출물**: 패키지별 라인 커버리지 % 보이도록 캡처

### Step 6. ArchUnit
IntelliJ에서 `ArchitectureTest` 실행 → 3개 규칙 모두 초록색 → 캡처

---

## 3. 스크린샷 파일 보관 위치

```
test-code/
└── evidence/
    ├── 01-gradle-test-result.png
    ├── 02-html-report-summary.png
    ├── 03-html-report-detail.png
    ├── 04-concurrency-result.png
    ├── 05-jacoco-coverage.png
    ├── 06-archunit-pass.png
    └── README.md         ← 각 캡처가 무엇을 보여주는지 한 줄 설명
```

`evidence/README.md` 템플릿:
```markdown
# 테스트 실행 산출물

| 파일 | 캡처 시점 | 무엇을 보여주는가 |
|------|-----------|------------------|
| 01-gradle-test-result.png | 2026-04-XX | 전체 46개 테스트 모두 통과, 소요 시간 ~1분 |
| 02-html-report-summary.png | 2026-04-XX | Successful 46 / Failed 0 / Skipped 0 |
| 03-html-report-detail.png | 2026-04-XX | 패키지별 분류 (concurrency, hold, queue, lock, ...) |
| 04-concurrency-result.png | 2026-04-XX | 100 thread 동시 홀드 시도 → 정확히 1 성공 |
| 05-jacoco-coverage.png | 2026-04-XX | 핵심 패키지(hold/lock/queue) 커버리지 70%+ |
| 06-archunit-pass.png | 2026-04-XX | 3개 의존성 규칙 모두 통과 |
```

---

## 4. JaCoCo 커버리지 도입 (선택 사항이지만 강력 추천)

`build.gradle`에 추가:

```groovy
plugins {
    // ... 기존 플러그인
    id 'jacoco'
}

jacoco {
    toolVersion = "0.8.12"
}

test {
    finalizedBy jacocoTestReport
}

jacocoTestReport {
    dependsOn test
    reports {
        xml.required = true
        html.required = true
    }
    afterEvaluate {
        classDirectories.setFrom(files(classDirectories.files.collect {
            fileTree(dir: it, exclude: [
                '**/dto/**',
                '**/config/**',
                '**/TicketingApplication.class',
                '**/*Application.class',
            ])
        }))
    }
}

jacocoTestCoverageVerification {
    violationRules {
        rule {
            element = 'PACKAGE'
            includes = ['com.inyoung.ticketing.hold.*', 'com.inyoung.ticketing.lock.*']
            limit {
                counter = 'LINE'
                value = 'COVEREDRATIO'
                minimum = 0.60   // 핵심 패키지 60% 이상
            }
        }
    }
}
```

**실행**:
```bash
./gradlew test jacocoTestReport
start build/reports/jacoco/test/html/index.html
```

### 면접 어필 포인트
- "전체 100% 커버리지"를 노리지 않는다 (DTO·Config은 의도적 제외)
- **핵심 비즈니스 로직**(hold, lock, queue)에 **선택과 집중**
- 60%라는 의미 있는 임계치 + `jacocoTestCoverageVerification` 으로 CI에서 강제

---

## 5. 면접에서 산출물 보여주는 시나리오

### 시나리오 A: "테스트 작성 경험 있나요?"
1. `test-code/README.md` 열어서 목차 보여주기
2. `test-code/05-test-catalog.md` 의 46개 테스트 표 펼치기
3. `evidence/02-html-report-summary.png` 보여주며 "46개 모두 통과합니다"

### 시나리오 B: "동시성 어떻게 보장했나요?"
1. `SeatHoldConcurrencyTest.java` 열기
2. 100 threads + CountDownLatch 패턴 설명
3. `evidence/04-concurrency-result.png` 보여주며 "정확히 1개 성공" 어필

### 시나리오 C: "유지보수 어떻게 하나요?"
1. `ArchitectureTest.java` 열기
2. 3가지 의존성 규칙 설명
3. "리뷰만으로 한계 → CI에서 자동 강제" 라인으로 마무리

---

## 6. 주의사항

- 스크린샷에 **개인정보·이메일·서버 IP** 가 보이지 않도록 모자이크 또는 트리밍
- 캡처 시점에 빌드가 **반드시 모두 통과한 상태** 여야 함 (실패가 보이면 마이너스)
- HTML 리포트 캡처 시 다크 모드보다는 **라이트 모드** (가독성 + 인쇄 호환)
