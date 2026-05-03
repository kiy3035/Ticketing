# 06. 면접 예상 Q&A

> 면접에서 테스트 관련 질문이 나왔을 때, **어떻게 답할지 시나리오**를 미리 준비한다.
> 답변은 항상 **(1) 결론 → (2) 근거 → (3) 코드/산출물 위치** 순서.

---

## Q1. "테스트 코드 작성해 보셨나요?"

### 답변 스크립트
> "네, 이번 티켓팅 프로젝트에서 단위·슬라이스·통합·동시성·아키텍처 5종류로 나누어 **총 42개의 테스트 메서드**를 작성했습니다.
> 특히 좌석 동시 선점 차단을 검증하기 위해 **100개 스레드가 같은 좌석에 동시 홀드를 시도해 정확히 1개만 성공하는 동시성 테스트**를 작성했고,
> Testcontainers로 실제 Redis·MySQL·Kafka 컨테이너를 띄워 **CI 환경에서도 외부 인프라 없이 동일한 검증**이 가능하도록 했습니다.
> 자세한 카탈로그는 `test-code/05-test-catalog.md` 에 정리해뒀습니다."

### 보여줄 산출물
- `test-code/README.md` — 목차
- `test-code/05-test-catalog.md` — 42개 테스트 표
- `evidence/02-html-report-summary.png`

---

## Q2. "테스트 피라미드 알고 계세요? 어떻게 적용하셨나요?"

### 답변 스크립트
> "네, 단위 테스트가 가장 많고 통합 테스트는 핵심 시나리오에만 적용하는 피라미드 형태로 구성했습니다.
> 단위 테스트는 Mockito로 ms 단위 실행이 가능해 **비즈니스 분기 검증**(429/404/200 등)에 사용했고,
> 통합 테스트는 Testcontainers를 띄우는 비용이 있어 **Redis Lua 스크립트의 원자성처럼 Mock으로 검증 불가능한 것**에만 사용했습니다.
> 대신 **동시성 테스트**라는 별도 층을 추가했는데, 분산 락의 정확성은 단위 테스트로는 절대 잡을 수 없는 race condition 영역이라 따로 격리했습니다."

### 핵심 키워드
- 단위 → 슬라이스 → 통합 → 동시성 → 아키텍처
- "Mock으로 검증 불가능한 것만 통합 테스트"
- "race condition은 단위 테스트로 못 잡는다"

---

## Q3. "Mock과 Stub 차이 아세요?"

### 답변 스크립트
> "Stub은 **반환값을 미리 지정**해 두는 가짜 객체이고, Mock은 거기에 더해 **호출 여부·횟수·인자까지 검증**할 수 있는 객체입니다.
> Mockito에서는 `when(...).thenReturn(...)` 이 Stub 역할이고, `verify(...)` 가 Mock의 검증 기능입니다.
> 예를 들어 `HoldServiceTest` 에서는 락 획득 후 `finally`에서 락이 반드시 해제되는지 검증하기 위해
> `verify(lockService).unlock(eq(lockKey), eq(lockToken))` 으로 호출 여부를 확인합니다."

### 코드 위치
`HoldServiceTest.java:140` — `verify(lockService).unlock(...)`

---

## Q4. "동시성을 테스트로 어떻게 검증하셨나요?"

### 답변 스크립트
> "**`CountDownLatch` 두 개**를 사용한 패턴으로 **진짜 동시 출발**을 만들었습니다.
> 첫 번째 latch(`readyLatch`)로 모든 스레드가 출발선에 도달했는지 확인하고,
> 두 번째 latch(`startLatch`)로 일제히 출발 신호를 줍니다.
> 그러면 100개 스레드가 거의 같은 시점에 `lockService.tryLock`을 호출하게 되어
> 실제 race condition이 발생하는 상태가 만들어집니다.
> 이 상태에서 `successCount` 가 정확히 1이 나오는지 AssertJ로 검증합니다."

### 코드 보여주기
```java
// SeatHoldConcurrencyTest.java
CountDownLatch readyLatch = new CountDownLatch(100);
CountDownLatch startLatch = new CountDownLatch(1);

for (int i = 0; i < 100; i++) {
    executor.submit(() -> {
        readyLatch.countDown();   // "준비 완료"
        startLatch.await();       // "출발 신호 대기"
        // ↓ 100개 스레드가 거의 동시에 진입
        holdService.createHold(request, userId);
    });
}
readyLatch.await();         // 모두 도착 대기
startLatch.countDown();     // 출발!
```

### 산출물
- `evidence/04-concurrency-result.png`
- `SeatHoldConcurrencyTest.java`

---

## Q5. "Testcontainers 왜 쓰셨나요? H2 같은 인메모리는 안 되나요?"

### 답변 스크립트
> "H2는 **MySQL 방언과 100% 호환되지 않습니다**. 특히 이 프로젝트는 Redis Lua 스크립트, MySQL의 `SELECT FOR UPDATE`, Kafka 발행을 함께 검증해야 하는데
> H2로는 Redis와 Kafka는 아예 검증이 불가능합니다.
> Testcontainers는 **실제 운영과 동일한 Docker 이미지(mysql:8.0, redis:7-alpine)** 를 띄우기 때문에,
> 환경 차이로 인한 'CI에서는 통과하는데 운영에서 깨지는' 시나리오를 미리 막을 수 있습니다.
> 또 `IntegrationTestBase` 추상 클래스로 컨테이너 설정을 한 곳에 모아서 테스트 클래스는 `extends` 만 하면 됩니다."

### 코드 위치
`src/test/java/.../support/IntegrationTestBase.java`

---

## Q6. "테스트 커버리지 몇 % 인가요?"

### 답변 스크립트 (정직하게)
> "전체 100%를 노리지 않습니다. DTO·Config은 의도적으로 제외하고, **핵심 비즈니스 패키지(hold, lock, queue)** 를 60% 이상으로 유지하는 정책입니다.
> JaCoCo `jacocoTestCoverageVerification` 으로 핵심 패키지의 임계치를 강제하고, 그 외에는 의미 있는 시나리오 커버에 집중합니다.
> 100% 커버리지는 'getter/setter까지 테스트'라는 무의미한 비용을 만들기 때문에 의도적으로 피했습니다."

### 핵심 메시지
- "**모든 라인을 테스트하지 않는다 — 의미 있는 분기를 테스트한다**"
- "임계치는 PR마다 자동 검증"

---

## Q7. "ArchUnit 들어보셨나요? 어떻게 쓰셨어요?"

### 답변 스크립트
> "네, 패키지 의존성 규칙을 **JUnit 테스트로 작성**해서 CI에서 자동 검증하는 도구입니다.
> 이 프로젝트에서는 3가지 규칙을 강제하고 있습니다:
> ① Controller가 Repository를 직접 import하면 빌드 실패,
> ② Domain이 Spring 프레임워크에 의존하면 빌드 실패,
> ③ Service가 Controller를 import하면 빌드 실패.
> 코드 리뷰만으로는 이런 의존성 위반을 놓치기 쉬운데, ArchUnit이 자동으로 잡아주니 실수가 머지되지 않습니다."

### 코드 위치
`ArchitectureTest.java:25-39`

---

## Q8. "통합 테스트가 너무 느리지 않나요? CI 시간 어떻게 관리하세요?"

### 답변 스크립트
> "맞습니다. 통합 테스트는 컨테이너 부팅에 30초 이상 걸리기 때문에 **단위 테스트와 분리**해야 합니다.
> 현재는 패키지 분리로 관리하고 있고, CI에서 `--tests "*ServiceTest"` 같은 패턴으로 단위만 먼저 실행해
> **빠른 피드백**을 받은 후, 통합 테스트를 별도 잡으로 분리할 수 있습니다.
> 또 Testcontainers는 **컨테이너 재사용**(reuse) 옵션이 있어서 로컬에서는 같은 컨테이너를 재사용해 속도를 더 줄일 수 있습니다."

### 추가 답변
- "느린 테스트는 별도 태그(`@Tag("slow")`)를 붙여 일반 빌드에서 제외할 수도 있습니다."

---

## Q9. "TDD 해보셨나요?"

### 답변 (정직하게 — 무리하게 거짓말하지 않기)
> "엄격한 의미의 TDD(Red-Green-Refactor)는 모든 기능에 적용하지는 않았습니다.
> 다만 **분산 락처럼 동시성이 중요한 핵심 로직**은 테스트를 먼저 설계하고 구현했습니다.
> '100명이 동시에 같은 좌석을 잡으면 1명만 성공해야 한다'는 명제를 테스트로 먼저 표현해두면,
> 구현 중에도 그 명제를 깨지 않는 방향으로 코드를 짜게 되더라고요.
> 비즈니스 분기 같은 부분은 구현 후에 테스트를 작성하는 'Test-After' 방식이 많았습니다."

### 핵심 메시지
- TDD를 안 했어도 **"왜 그 부분만 TDD를 했는지"** 를 설명할 수 있으면 OK
- 거짓말 금지

---

## Q10. "테스트가 깨질 때 어떻게 디버깅하세요?"

### 답변 스크립트
> "단위 테스트가 깨지면 IntelliJ에서 디버그 모드로 실행해 브레이크포인트로 stub 동작을 확인합니다.
> 통합 테스트가 깨지면 보통 **컨테이너 로그**가 원인을 말해줘서, `logging.level.org.testcontainers=DEBUG` 로 컨테이너 출력을 확인합니다.
> 동시성 테스트가 가끔 flaky 하면 **`Thread.sleep`을 쓰고 있는지** 부터 점검합니다 — 시간 의존 테스트는 CI에서 깨지기 쉬워서
> 가능하면 `CountDownLatch`나 `Awaitility` 같은 동기화 도구로 대체합니다."

---

## Q11. "Mockito 의 `@Mock` vs `@MockitoBean` 차이?"

### 답변 스크립트
> "`@Mock`은 **순수 단위 테스트** 에서 의존성을 가짜로 대체할 때 씁니다. Spring 컨텍스트가 필요 없습니다.
> `@MockitoBean`(Spring Boot 3.4부터, 이전엔 `@MockBean`)은 **Spring 컨텍스트가 로딩된 상태**에서 컨테이너 안의 빈을 Mock으로 교체할 때 씁니다.
> 이 프로젝트의 `HoldControllerIntegrationTest` 처럼 `@WebMvcTest` 안에서 `HoldService`를 Mock 처리할 때 `@MockitoBean`을 사용했습니다."

---

## Q12. "테스트가 비즈니스 변경에 너무 자주 깨지지 않나요?"

### 답변 스크립트
> "그 부분이 가장 어려운 트레이드오프입니다.
> 저는 **'구현 디테일이 아니라 동작(behavior)을 테스트'** 한다는 원칙을 따릅니다.
> 예를 들어 `HoldService` 단위 테스트는 'Redis Template의 어떤 메서드를 몇 번 호출했는지'가 아니라
> '락 획득 실패 시 429를 던지는지', '성공 시 `unlock`을 반드시 호출하는지' 같은 **외부에서 관찰 가능한 행동**을 검증합니다.
> 그러면 내부 구현이 바뀌어도 테스트가 깨지지 않습니다."

---

## 답변하면서 절대 하지 말아야 할 것

- ❌ "테스트 다 작성합니다" 같은 모호한 답변 → 숫자(21개), 종류(5가지)로 답하기
- ❌ "100% 커버리지 달성했습니다" → 신뢰도 떨어짐. 60% + 의도적 제외 정책이 더 프로페셔널
- ❌ TDD 안 해봤는데 "당연히 TDD 합니다" → 거짓말 들킴
- ❌ "동시성은 어렵습니다" → 어렵다고 인정만 하지 말고 어떻게 검증했는지 코드로 보여주기
- ❌ Mockito 모르는데 아는 척 → "사용해본 적 없어 추가 학습 필요합니다" 가 더 좋음

## 답변할 때 항상 챙길 것

- ✅ **숫자**로 답하기 (테스트 21개, 동시성 100 threads, 커버리지 60%)
- ✅ **파일 경로** 알려주기 (`test-code/05-test-catalog.md`, `SeatHoldConcurrencyTest.java`)
- ✅ **트레이드오프** 의식 보이기 (왜 100% 커버리지 안 했는지, 왜 통합 테스트만 Testcontainers 쓰는지)
- ✅ **운영 감각** 보이기 (CI에서 자동화, ArchUnit으로 리뷰 한계 보완, flaky test 회피)
