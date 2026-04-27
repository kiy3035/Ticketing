# 02. 테스트 유형별 작성 컨벤션

## 0. 공통 컨벤션

### Given-When-Then 구조
모든 테스트 메서드 본문은 다음 3구간으로 명확히 나뉘어야 한다.

```java
@Test
void 메서드명_시나리오_기대결과() {
    // given: 사전 조건 (Mock stub, 데이터 셋업)
    when(seatRepository.findById(1L)).thenReturn(Optional.of(seat));

    // when: 실제 동작 호출 (한 줄이 이상적)
    var response = holdService.createHold(request, "user1");

    // then: 결과 검증
    assertThat(response.holdToken()).isNotBlank();
}
```

### 메서드 네이밍 규칙
`메서드명_조건_기대결과` 형식. 한국어 `@DisplayName` 병행 권장.

```java
@Test
@DisplayName("락 획득 실패 시 429 반환")
void createHold_throws429_whenLockFails() { ... }
```

### Assertion 라이브러리: AssertJ
- JUnit 기본 `assertEquals`보다 **AssertJ `assertThat()`** 사용
- 메서드 체이닝으로 가독성 우수
- 한국어 메시지 추가: `.as("동시 시도 시 1개만 성공")`

```java
assertThat(successCount.get())
    .as("동시 홀드 시도 시 정확히 1명만 성공해야 한다")
    .isEqualTo(1);
```

---

## 1. 단위 테스트 (Unit Test)

### 위치
`src/test/java/.../서비스명Test.java`

### 어노테이션
```java
@ExtendWith(MockitoExtension.class)
class HoldServiceTest {
    @Mock private SeatRepository seatRepository;   // 가짜 객체
    @Mock private LockService lockService;

    private HoldService holdService;               // 실제 객체 (테스트 대상)

    @BeforeEach
    void setUp() {
        holdService = new HoldService(seatRepository, lockService, ...);
    }
}
```

### 핵심 패턴
| 패턴 | 사용 예시 |
|------|----------|
| `@Mock` | 의존성을 가짜로 대체 |
| `when(...).thenReturn(...)` | Mock의 반환값 지정 |
| `verify(mock).method(...)` | 메서드가 호출되었는지 검증 (예: 락 해제 여부) |
| `any()`, `eq(value)` | 인자 매처 |
| `assertThatThrownBy()` | 예외 발생 검증 |

### 좋은 단위 테스트 체크리스트
- [ ] **하나의 분기만** 검증 (한 테스트 = 한 시나리오)
- [ ] 외부 의존성은 모두 `@Mock` 처리 → DB/Redis 없이 ms 단위 실행
- [ ] `finally` 블록의 락 해제 같은 **리소스 정리**도 `verify()`로 검증
- [ ] 에러 케이스(404, 429, 409)를 정상 케이스만큼 빠짐없이 작성

### 참고 파일
- `src/test/java/com/inyoung/ticketing/lock/RedisLockServiceTest.java` (가장 단순)
- `src/test/java/com/inyoung/ticketing/queue/service/QueueServiceTest.java`
- `src/test/java/com/inyoung/ticketing/hold/service/HoldServiceTest.java` (가장 본격적)

---

## 2. 슬라이스 테스트 (Web Layer Slice)

### 위치
`src/test/java/.../controller/컨트롤러명IntegrationTest.java`

### 어노테이션
```java
@WebMvcTest(HoldController.class)               // 컨트롤러 1개만 로딩 (빠름)
class HoldControllerIntegrationTest {
    @Autowired private MockMvc mockMvc;         // HTTP 가상 호출
    @MockitoBean private HoldService holdService; // 서비스는 Mock
}
```

### 핵심 패턴
```java
mockMvc.perform(post("/api/holds")
        .with(authentication(auth))               // 인증 주입
        .with(csrf())                             // CSRF 토큰
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(request)))
    .andExpect(status().isOk())                   // HTTP 상태 코드
    .andExpect(jsonPath("$.data.token").value("token-123")); // JSON 필드
```

### 검증 대상
- HTTP 상태 코드 (200/201/400/404/429)
- JSON 응답 형식 (`$.data.field`)
- 인증/인가 동작
- 요청 본문 검증 (`@Valid`)

### 참고 파일
- `src/test/java/com/inyoung/ticketing/hold/controller/HoldControllerIntegrationTest.java`
- `src/test/java/com/inyoung/ticketing/queue/controller/QueueControllerIntegrationTest.java`

---

## 3. 통합 테스트 (Integration Test)

### 위치
`src/test/java/.../서비스명Test.java` (extends IntegrationTestBase)

### 어노테이션
```java
class IdempotencyServiceTest extends IntegrationTestBase {
    @Autowired private IdempotencyService idempotencyService;  // 실제 빈
}
```

### 핵심 인프라: `IntegrationTestBase`
```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
public abstract class IntegrationTestBase {
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");
    @Container static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine");
    @Container static KafkaContainer kafka = new KafkaContainer(...);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        // ...
    }
}
```

### 검증 대상
- Redis Lua 스크립트의 **실제 원자성** (Mock으로는 불가)
- MySQL 트랜잭션 롤백 동작
- Kafka 메시지 발행 → 컨슈머 수신
- 멱등성 키 선점 race condition

### 참고 파일
- `src/test/java/com/inyoung/ticketing/idempotency/IdempotencyServiceTest.java`
- `src/test/java/com/inyoung/ticketing/ratelimit/RateLimitServiceTest.java`

---

## 4. 동시성 테스트 (Concurrency Test)

### 위치
`src/test/java/.../concurrency/`

### 핵심 패턴: `CountDownLatch` 2단계 게이트
```java
int threadCount = 100;
CountDownLatch readyLatch = new CountDownLatch(threadCount);  // 모든 스레드 준비됐는지
CountDownLatch startLatch = new CountDownLatch(1);            // 동시 출발 신호

for (int i = 0; i < threadCount; i++) {
    executor.submit(() -> {
        readyLatch.countDown();   // "나 준비됐어"
        startLatch.await();       // "출발 신호 대기"

        // ↓ 100개 스레드가 거의 동시에 이 라인 진입
        Optional<String> token = lockService.tryLock(lockKey, ttl);
        if (token.isPresent()) successCount.incrementAndGet();
    });
}

readyLatch.await();         // 모든 스레드가 대기 위치 도달
startLatch.countDown();     // 출발!
// ... future.get()으로 모든 작업 완료 대기
```

### 왜 두 개의 Latch?
- `readyLatch`: 100개 스레드가 모두 `await()` 직전까지 도달했는지 확인
- `startLatch`: 일제히 출발 → "스레드 0이 락을 잡고 끝낸 다음 스레드 1이 시작" 같은 순차 실행 방지
- 결과: **진짜 race condition** 발생 가능한 상태에서 테스트

### 검증 대상
- 100 스레드 동시 좌석 홀드 → 1개만 성공 (`SeatHoldConcurrencyTest`)
- 50 스레드 동시 락 시도 → 1개만 토큰 획득 (`RedisLockConcurrencyTest`)

### 참고 파일
- `src/test/java/com/inyoung/ticketing/concurrency/SeatHoldConcurrencyTest.java`
- `src/test/java/com/inyoung/ticketing/concurrency/RedisLockConcurrencyTest.java`

---

## 5. 아키텍처 테스트 (ArchUnit)

### 핵심 패턴
```java
@AnalyzeClasses(packagesOf = TicketingApplication.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule controllersDoNotTouchRepositories = noClasses()
        .that().resideInAPackage("..controller..")
        .should().dependOnClassesThat().resideInAPackage("..repository..");
}
```

### 검증 대상
- Controller가 Repository를 직접 import하면 빌드 실패
- Domain이 Spring에 의존하면 빌드 실패
- Service가 Controller를 참조하면 빌드 실패 (순환 방지)

### 참고 파일
- `src/test/java/com/inyoung/ticketing/architecture/ArchitectureTest.java`

---

## 6. 새 테스트 작성 시 의사결정 트리

```
질문: 무엇을 검증하고 싶은가?

├─ 단일 클래스의 비즈니스 분기?
│   → 단위 테스트 (@ExtendWith(MockitoExtension.class))
│
├─ HTTP 계약 (상태 코드 / JSON 형식)?
│   → 슬라이스 테스트 (@WebMvcTest)
│
├─ 실제 Redis Lua / MySQL 트랜잭션?
│   → 통합 테스트 (extends IntegrationTestBase)
│
├─ 분산 락 / 멀티스레드 race condition?
│   → 동시성 테스트 (CountDownLatch + ExecutorService)
│
└─ 패키지 의존성 규칙?
    → ArchUnit (@ArchTest)
```
