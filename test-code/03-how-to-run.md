# 03. 테스트 실행 방법

## 1. 사전 준비

### 1-1. JDK 21
```bash
java -version
# openjdk version "21.x.x" 이어야 함
```

### 1-2. Docker (통합/동시성 테스트용)
- 단위 테스트만 실행할 거면 Docker 불필요
- 통합 테스트는 Testcontainers가 자동으로 MySQL/Redis/Kafka 컨테이너를 띄움
- Docker Desktop 또는 Rancher Desktop 실행 중이어야 함

```bash
docker ps   # 실행 중이면 빈 목록이라도 표시됨
```

---

## 2. Gradle 명령어

### 2-1. 전체 테스트 실행
```bash
./gradlew test
```
- 단위 + 슬라이스 + 통합 + 동시성 + 아키텍처 모두 실행
- Docker 필수, 첫 실행 시 컨테이너 이미지 다운로드로 1~2분 소요

### 2-2. 단위 테스트만 (빠름)
```bash
# 패턴으로 골라 실행
./gradlew test --tests "*ServiceTest" --tests "*LockServiceTest"

# 특정 클래스
./gradlew test --tests "com.inyoung.ticketing.lock.RedisLockServiceTest"

# 특정 메서드
./gradlew test --tests "com.inyoung.ticketing.lock.RedisLockServiceTest.tryLock_returnsToken_whenSetIfAbsentSucceeds"
```

### 2-3. 동시성 테스트만
```bash
./gradlew test --tests "com.inyoung.ticketing.concurrency.*"
```

### 2-4. 아키텍처 테스트만
```bash
./gradlew test --tests "*ArchitectureTest"
```

### 2-5. 캐시 무시하고 강제 재실행
```bash
./gradlew test --rerun-tasks
```
- Gradle이 "변경 없음"이라며 스킵할 때 사용

### 2-6. 빌드 결과까지 모두 정리 후 재실행
```bash
./gradlew clean test
```

---

## 3. IntelliJ에서 실행

### 3-1. 단일 테스트 메서드 실행
- 테스트 메서드명 옆 ▶ 초록색 화살표 클릭
- 또는 메서드 안에 커서 두고 `Ctrl + Shift + F10`

### 3-2. 클래스 전체 실행
- 클래스명 옆 ▶ 클릭

### 3-3. 디버깅
- ▶ 옆의 🐛 아이콘 클릭 또는 `Shift + F9`
- 브레이크포인트는 클래스 라인 번호 좌측 클릭

### 3-4. 실행 결과 보기
- 하단 Run 윈도우에서 트리 형태로 표시
- 실패한 테스트는 빨간색, AssertJ 메시지가 출력됨

---

## 4. 리포트 위치

테스트 실행 후 다음 위치에 HTML 리포트가 생성된다.

```
build/
├── reports/
│   ├── tests/
│   │   └── test/
│   │       └── index.html        ← 전체 테스트 리포트 (보기 좋음)
│   └── jacoco/                   ← 커버리지 리포트 (JaCoCo 도입 후)
│       └── test/
│           └── html/
│               └── index.html
└── test-results/
    └── test/
        └── *.xml                 ← CI에서 파싱하는 JUnit XML
```

### 리포트 열기

#### Windows
```bash
start build/reports/tests/test/index.html
```

#### macOS
```bash
open build/reports/tests/test/index.html
```

#### Linux
```bash
xdg-open build/reports/tests/test/index.html
```

---

## 5. 실패 시 디버깅

### 5-1. 자세한 출력
```bash
./gradlew test --info
```
- Gradle이 무엇을 하는지 자세히 보여줌

### 5-2. 스택트레이스 전체
```bash
./gradlew test --stacktrace
```

### 5-3. 테스트 로그 표시
`build.gradle`에 다음 추가:
```groovy
tasks.named('test') {
    useJUnitPlatform()
    testLogging {
        events 'passed', 'skipped', 'failed'
        showStandardStreams = true   // System.out.println 출력 표시
    }
}
```

### 5-4. Testcontainers 로그
- Docker 컨테이너 로그가 필요하면 `application.properties`에:
```properties
logging.level.org.testcontainers=DEBUG
logging.level.com.github.dockerjava=INFO
```

---

## 6. CI에서 실행 (GitHub Actions 예시)

```yaml
# .github/workflows/test.yml (참고용 — 현재 프로젝트엔 없을 수 있음)
name: Test
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: 21
          distribution: temurin
      - name: Run tests
        run: ./gradlew test
      - name: Upload report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-report
          path: build/reports/tests/test/
```

> ✅ Testcontainers는 GitHub Actions의 Docker 환경에서 그대로 동작한다.
> 별도 인프라 셋업 없이 CI에서 실제 Redis/MySQL을 띄울 수 있는 게 핵심 장점.

---

## 7. 트러블슈팅

| 증상 | 원인 | 해결 |
|------|------|------|
| `Cannot connect to Docker daemon` | Docker 미실행 | Docker Desktop 시작 |
| `Port already in use` | 이전 컨테이너가 남아있음 | `docker ps -a` 확인 후 정리 |
| `Container startup timeout` | 이미지 다운로드 지연 | 첫 실행 시 정상, 재실행 |
| `UnnecessaryStubbingException` | Mockito 엄격 모드에서 미사용 stub | 해당 stub 제거 또는 lenient 적용 |
| `WebMvcTest가 빈을 못 찾음` | Security 필터에 필요한 빈이 Mock 안됨 | `@MockitoBean`으로 추가 |

---

## 8. 자주 쓰는 명령 모음

```bash
# 가장 자주 쓸 명령들
./gradlew test --tests "*ServiceTest"                    # 단위만 빠르게
./gradlew test --tests "*ConcurrencyTest"                # 동시성 검증
./gradlew test                                            # 전체 (PR 전)
./gradlew clean test --rerun-tasks                        # 캐시 의심될 때
start build/reports/tests/test/index.html                 # 결과 보기
```
