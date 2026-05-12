## 16. SSE 다중 인스턴스 브로드캐스트 통합 테스트 (시나리오 설계)

> 백엔드를 nginx 뒤 2대로 스케일아웃했을 때 발생하는 **SSE 알림 누락 문제**를 Redis Pub/Sub 으로 어떻게 해결했는지 코드로 증명하는 산출물.
> 면접에서 "SSE 같은 stateful 연결을 어떻게 다중 인스턴스에서 다뤘나요?" 라는 질문에 **테스트 코드 + 통과 리포트** 로 답할 수 있게 한다.

---

## 1. 배경 — 왜 이 테스트가 포폴로 가치가 있나

### 다중 인스턴스 환경에서 SSE 의 본질적 문제

스케일아웃은 보통 stateless 서비스를 전제로 한다. 그런데 SSE 는 **인스턴스 로컬 메모리에 `SseEmitter` 를 보유**해야 하는 stateful 한 자원이다. nginx 가 사용자를 app1 로 라우팅했어도, **Kafka 컨슈머가 app2 에서 실행되면 알림을 받지 못한다**.

```
사용자 X ──nginx(least_conn)──▶ app1  [emitters: { X → SseEmitter }]
                                  ▲
                                  │ 어떻게 도달?
                                  │
결제 완료 Kafka 이벤트 ──────▶ app2  [emitters: { } 비어있음 → 누락!]
```

### 흔한 오답과 진짜 해법

| 접근 | 한계 |
|------|------|
| **nginx Sticky Session** (`ip_hash`) | 같은 사용자를 같은 앱으로 라우팅하지만, **Kafka 컨슈머 실행 인스턴스는 독립** → 본질 해결 안 됨 |
| **세션 클러스터링** (Redis Session) | SSE 는 세션이 아닌 long-lived HTTP 연결. 적용 불가 |
| **클라이언트 폴링으로 회귀** | 푸시 모델의 즉시성·인프라 이점 포기 |
| **Redis Pub/Sub 브로드캐스트** ✅ | 발행은 어느 인스턴스든 가능, 모든 인스턴스가 구독 → 자기 emitter 보유분만 send |

### 이 프로젝트의 해법

**알림 전달 경로만 Redis 를 경유**한다. SSE 연결 자체는 인스턴스 로컬에 그대로 두고(과한 설계 회피), 발행 시점에만 Redis `PUBLISH sse:notify:{userId}` 를 거친다. 모든 앱 인스턴스가 `sse:notify:*` 를 PSUBSCRIBE 하고 있어 자기가 보유한 emitter 만 send 한다.

---

## 2. 핵심 구현 (검증 대상)

```java
// SseNotificationService — MessageListener 구현
public class SseNotificationService implements MessageListener {

    public static final String CHANNEL_PREFIX = "sse:notify:";
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void sendNotification(String userId, Object data) {
        String json = objectMapper.writeValueAsString(data);
        redisTemplate.convertAndSend(CHANNEL_PREFIX + userId, json);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), UTF_8);
        String userId = channel.substring(CHANNEL_PREFIX.length());
        SseEmitter emitter = emitters.get(userId);
        if (emitter == null) return;  // 다른 인스턴스가 담당
        emitter.send(SseEmitter.event().name("notification").data(item));
    }
}

// SseRedisConfig — RedisMessageListenerContainer 가 PSUBSCRIBE 등록
container.addMessageListener(
    sseNotificationService,
    new PatternTopic(SseNotificationService.CHANNEL_PREFIX + "*")
);
```

### 핵심 설계 포인트

| 항목 | 설명 |
|------|------|
| Pub/Sub 채널 = `sse:notify:{userId}` | userId 별로 채널 분리 → 다른 사용자에게 영향 없음 (격리) |
| 발행 측은 `convertAndSend` 만 호출 | 어느 emitter 가 어디 있는지 알 필요 없음 (위치 투명성) |
| 구독 측은 `emitters.get()` 으로 자기 보유분 확인 | 없으면 조용히 return — 정상 흐름 |
| SSE 연결 자체는 인스턴스 로컬 유지 | 연결 메타까지 Redis 로 올리면 과한 설계. 발행 경로만 Redis 경유 |
| `DataAccessException` 흡수 | Redis 장애 시 SSE 알림은 best-effort 로 포기 — Kafka 컨슈머 재시도/DLT 연쇄 차단 |

---

## 3. 4가지 시나리오

`SseNotificationMultiInstanceIntegrationTest extends IntegrationTestBase` (Testcontainers Redis 사용)

> 같은 JVM 안에서 두 개의 `SseNotificationService` 인스턴스(A, B)와 각각의 `RedisMessageListenerContainer` 를 띄워 **"두 대의 서로 다른 앱 인스턴스"** 를 시뮬레이션한다. 두 JVM 을 띄우지 않는 이유는 SSE 의 본질적 검증 포인트가 "발행 인스턴스 ≠ 구독 인스턴스" 일 때의 동작이고, 이는 같은 Redis 를 공유하는 두 listener container 만으로 동등하게 증명되기 때문.

| # | 시나리오 | 검증 내용 | 왜 중요한가 |
|---|----------|-----------|-------------|
| 1 | **cross-instance broadcast (핵심)** | A 에 user1 emitter 등록 → B(다른 인스턴스)에서 sendNotification → A 의 emitter 가 메시지 수신 | 운영 케이스: 사용자가 app1 에 SSE 연결, Kafka 컨슈머는 app2 에서 실행 |
| 2 | **broadcast 본성** | 같은 userId 가 A·B 양쪽에 연결돼 있으면 양쪽 emitter 모두 send 호출 | Pub/Sub 의 "PUBLISH 가 모든 구독자에게 전달" 본성 검증 |
| 3 | **사용자 격리** | A 에 user1 만 연결, B 에서 user2 에게 publish → user1 emitter 는 호출되지 않음 | 채널 prefix(`sse:notify:{userId}`) 가 사용자별로 분리되는지 |
| 4 | **no-op** | 어느 인스턴스에도 emitter 없는 사용자에게 publish → 예외 없이 통과 | 사용자가 SSE 연결 끊은 직후 발생한 알림이 컨슈머를 죽이지 않는지 |

---

## 4. 테스트 코드 위치 & 뼈대

### 통합 테스트
```
src/test/java/com/inyoung/ticketing/notification/
└── SseNotificationMultiInstanceIntegrationTest.java
```

### 핵심 setUp — "두 번째 앱 인스턴스" 시뮬레이션

```java
@BeforeEach
void setUp() {
    instanceA.removeAllConnections();

    // instanceB 와 그 전용 listener container 를 띄워 "2대 운영" 환경 시뮬레이션
    instanceB = new SseNotificationService(redisTemplate, objectMapper);
    containerB = new RedisMessageListenerContainer();
    containerB.setConnectionFactory(connectionFactory);
    containerB.afterPropertiesSet();
    containerB.start();
    containerB.addMessageListener(
        instanceB,
        new PatternTopic(SseNotificationService.CHANNEL_PREFIX + "*")
    );
}
```

### 시나리오 1 핵심 코드 (cross-instance broadcast)

```java
@Test
@DisplayName("B(다른 인스턴스)에서 발행한 알림을 A(연결 보유 인스턴스)가 수신해 emitter 에 전달한다")
void crossInstanceBroadcast_publisherIsB_subscriberIsA() throws Exception {
    // given: 사용자 X 가 instanceA 에 SSE 연결 (실제 emitter 대신 spy 로 send 호출 캡처)
    String userId = "user-" + UUID.randomUUID();
    SseEmitter spyEmitterOnA = spy(new SseEmitter(60_000L));
    injectEmitter(instanceA, userId, spyEmitterOnA);

    // when: instanceB 에서 같은 사용자에게 알림 발행 (= 다른 앱 서버가 Kafka 이벤트 처리)
    instanceB.sendNotification(userId,
        new NotificationItemResponse("PAYMENT_COMPLETED", "결제가 완료되었습니다.", Instant.now()));

    // then: Redis Pub/Sub 비동기라 Awaitility 로 대기, A 의 emitter 에 send 호출됨
    Awaitility.await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(() ->
            verify(spyEmitterOnA, atLeastOnce())
                .send(any(SseEmitter.SseEventBuilder.class)));
}
```

### 헬퍼 — emitter 직접 주입 (reflection)

```java
@SuppressWarnings("unchecked")
private void injectEmitter(SseNotificationService instance, String userId, SseEmitter emitter) throws Exception {
    Field field = SseNotificationService.class.getDeclaredField("emitters");
    field.setAccessible(true);
    Map<String, SseEmitter> map = (Map<String, SseEmitter>) field.get(instance);
    map.put(userId, emitter);
}
```

> **왜 reflection 으로 emitter 를 직접 주입했나?**
> `createConnection()` 으로 만들면 실제 `SseEmitter` 가 생성돼 `spy/verify` 가 어렵다. mockito spy 로 감싼 emitter 를 그대로 emitters 맵에 등록해야 `verify(emitter).send(...)` 로 호출 검증이 가능하다. 운영 코드는 건드리지 않고 테스트에서만 사용.

### Awaitility 비동기 검증

Redis Pub/Sub 은 비동기다. publish → broker → subscriber 콜백 사이에 ms 단위 지연이 있다. `Thread.sleep` 대신 `Awaitility.await().atMost(3s).untilAsserted(...)` 로 **검증이 통과할 때까지 폴링** 한다. 시나리오 3(격리) 은 반대로 `during(1s)` 로 **1초 동안 한 번도 호출되지 않음** 을 검증.

---

## 5. 실행 방법 + 기대 출력

```bash
./gradlew test --tests SseNotificationMultiInstanceIntegrationTest

# 기대 출력
SseNotificationMultiInstanceIntegrationTest > B(다른 인스턴스)에서 발행한 알림을 A(연결 보유 인스턴스)가 수신해 emitter 에 전달한다 PASSED
SseNotificationMultiInstanceIntegrationTest > 같은 userId 가 A·B 양쪽에 연결돼 있으면 두 emitter 모두 메시지를 수신한다 PASSED
SseNotificationMultiInstanceIntegrationTest > user2 에게 보낸 알림이 user1 emitter 로 전달되지 않는다 (채널 격리) PASSED
SseNotificationMultiInstanceIntegrationTest > 어느 인스턴스에도 emitter 가 없는 사용자에게 publish 해도 예외 없이 통과한다 PASSED

BUILD SUCCESSFUL
```

상세 결과·콘솔 출력은 [`evidence/sse-multi-instance-test-result.md`](evidence/sse-multi-instance-test-result.md) 참조.

---

## 6. 면접 어필 포인트

1. **"스케일아웃 = stateless" 라는 흔한 오답을 피했다** — SSE 는 본질적으로 stateful. nginx Sticky Session 으로는 Kafka 컨슈머 인스턴스를 제어할 수 없어 본질 해결 안 됨을 명시적으로 인지·문서화
2. **Pub/Sub 으로 위치 투명성 확보** — 발행 측은 emitter 가 어디 있는지 모르고, 구독 측은 자기 보유분만 send. 인스턴스 N 대 추가에 코드 변경 없음
3. **과한 설계 회피** — SSE 연결 자체를 Redis 로 올리면 emitter 직렬화·복원 등 복잡도 폭증. **알림 전달 경로만** Redis 경유로 최소 침습 해결
4. **Pub/Sub 비동기 특성 의식한 테스트** — `Awaitility.await().atMost()` 로 폴링, `during()` 으로 격리 검증. flaky 없는 자동화
5. **운영 회복력** — `DataAccessException` 흡수로 Redis 장애가 Kafka 컨슈머 재시도/DLT 연쇄로 번지지 않게 차단

---

## 7. 면접 답변 스크립트

### Q1. SSE 같은 stateful 연결을 다중 인스턴스에서 어떻게 다뤘나요?

> "SSE 는 인스턴스 로컬 메모리에 `SseEmitter` 를 보유해야 하는 stateful 한 자원입니다. 사용자가 app1 에 연결돼 있어도 결제 완료 Kafka 이벤트가 app2 에서 컨슈밍되면 알림이 누락됩니다.
> 이걸 Redis Pub/Sub 브로드캐스트로 해결했습니다. 알림 발행은 `redisTemplate.convertAndSend("sse:notify:{userId}", json)` 한 줄이고, 모든 앱 인스턴스가 `sse:notify:*` 를 PSUBSCRIBE 하고 있다가 자기 `emitters` 맵에 해당 userId 가 있을 때만 `emitter.send()` 를 실행합니다.
> Testcontainers Redis 로 두 SseNotificationService 인스턴스를 띄워 cross-instance broadcast·격리·broadcast 본성·no-op 4 시나리오를 검증했습니다."

### Q2. 왜 nginx Sticky Session 으로 안 풀고 Pub/Sub 으로 갔나요?

> "Sticky Session 은 같은 사용자를 같은 앱으로 라우팅해주지만, Kafka 컨슈머가 어느 인스턴스에서 실행되는지는 통제 못 합니다. 사용자가 app1 에 라우팅돼도 컨슈머가 app2 에서 돌면 결국 누락됩니다.
> 본질적인 해결은 '발행 인스턴스와 SSE 연결 인스턴스를 분리할 수 있는 메커니즘'이고, Redis Pub/Sub 이 정확히 그 역할을 합니다."

### Q3. SSE 연결 자체도 Redis 로 옮길 수 있지 않나요?

> "이론상 가능하지만 과한 설계라고 판단했습니다. `SseEmitter` 는 직렬화 가능한 객체가 아니라서 인스턴스 간 이전이 어렵고, 옮기더라도 long-lived HTTP 연결의 socket 자체는 옮길 수 없습니다.
> 핵심은 '연결을 어디 두느냐' 가 아니라 '알림이 올바른 인스턴스로 가느냐' 입니다. 연결은 인스턴스 로컬에 두고 **알림 전달 경로만** Redis 경유로 충분합니다 — 최소 침습 해결."

### Q4. 같은 사용자가 두 인스턴스에 동시 연결되는 케이스는?

> "운영 케이스로는 흔치 않지만 — 모바일/PC 동시 접속 — broadcast 본성상 자연스럽게 양쪽 모두 받습니다. 시나리오 2 에서 두 emitter 모두 send 호출되는 걸 검증했습니다.
> 단점이라기보다 'Pub/Sub 모델의 일관된 동작' 으로 보고 있고, 사용자가 한 인스턴스에만 연결 정책이 필요하면 `createConnection()` 의 기존 emitter `removeConnection` 로직(이미 코드에 있음)을 활용할 수 있습니다."

### Q5. Redis 가 죽으면 SSE 도 다 죽나요?

> "발행 경로의 `DataAccessException` 을 명시적으로 흡수합니다. SSE 알림은 best-effort 라 Redis 장애 시 해당 알림은 포기하고 로그만 남깁니다.
> 흡수하지 않으면 Kafka 컨슈머까지 예외가 전파돼 재시도 3회 후 DLT 로 가는 과잉 반응이 발생합니다. SSE 푸시 실패 때문에 정상 결제 이벤트가 DLT 로 흘러가는 걸 막는 의도적 설계입니다."

---

## 8. 완료 체크리스트

- [x] `SseNotificationService` MessageListener 구현 + Pub/Sub 발행 경로
- [x] `SseRedisConfig` `RedisMessageListenerContainer` PSUBSCRIBE 등록
- [x] 4 시나리오 통합 테스트 (`SseNotificationMultiInstanceIntegrationTest`)
- [x] Awaitility 비동기 검증 (flaky 방지)
- [x] reflection 헬퍼로 spy emitter 직접 주입 (운영 코드 미변경)
- [x] `DataAccessException` 흡수로 Kafka 컨슈머 보호
- [x] `evidence/sse-multi-instance-test-result.md` 실행 결과 캡처
- [x] `05-test-catalog.md` 와 `README.md` 에 16번 항목 추가
