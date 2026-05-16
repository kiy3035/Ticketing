# SSE 다중 인스턴스 — Redis Pub/Sub 브로드캐스트

> 앱 서버 2대(nginx 뒤) 환경에서 SSE 알림이 누락되는 문제를 Redis Pub/Sub 으로 푼 구조 정리.
> 코드 라인 단위로 따라가는 공부 노트.

## 핵심 파일

| 파일 | 역할 |
|------|------|
| `notification/service/SseNotificationService.java` | `SseEmitter` 보관 맵 + `sendNotification`(발행) + `onMessage`(수신 콜백). `MessageListener` 구현 |
| `notification/controller/NotificationSseController.java` | `GET /api/notifications/stream` — 사용자별 SSE 연결 생성 |
| `config/SseRedisConfig.java` | `RedisMessageListenerContainer` 빈 + `sse:notify:*` 패턴 PSUBSCRIBE 등록 |
| `payment/service/PaymentNotificationService.java` 등 | 알림 발행 측 — `sseNotificationService.sendNotification(userId, dto)` 호출 |

Redis 키(채널) 카탈로그: [`06-redis-kafka-reference.md`](06-redis-kafka-reference.md) §1.2 의 `sse:notify:{userId}` 행.

---

## 1. 문제 — 왜 단순 인메모리 emitter 는 안 되나

### 단일 인스턴스 전제로 짠 초기 구조

```java
// 변경 전 (안 됨)
public void sendNotification(String userId, Object data) {
    SseEmitter emitter = emitters.get(userId);
    if (emitter != null) emitter.send(...);
}
```

`emitters` 는 `ConcurrentHashMap<String, SseEmitter>` — **이 JVM 메모리에만 있는 맵**. 사용자가 app1 에 연결돼 있으면 그 emitter 는 app1 의 맵에만 있다.

### 2대 구성에서 깨지는 시나리오

```
사용자 X  ──nginx(least_conn)──▶  app1   [emitters: { X → SseEmitter }]
                                    ▲
                                    │  알림이 도달하지 못함
                                    │
결제 완료 Kafka 이벤트  ─────────▶  app2  [emitters: { } 비어있음]
```

Kafka 컨슈머가 app2 에서 실행되면 `emitters.get(X)` → null → 알림 누락. 사용자 입장에선 결제는 됐는데 "결제 완료" 푸시가 안 옴.

---

## 2. 안 풀리는 흔한 접근들

| 접근 | 왜 안 풀리는가 |
|------|----------------|
| nginx Sticky Session (`ip_hash`) | **요청 라우팅**만 같은 앱으로 고정. Kafka 컨슈머는 nginx 와 무관하게 두 앱 중 어느 한쪽에서 실행됨 → 본질 미해결 |
| 세션 클러스터링 (Spring Session + Redis) | SSE 는 세션이 아닌 long-lived HTTP 응답 스트림. `SseEmitter` 자체가 직렬화 가능 객체가 아님 |
| 클라이언트 폴링으로 회귀 | 푸시 모델 포기 — 즉시성 손실, 폴링 트래픽 비용 |

### 정리된 핵심

문제는 **"연결을 어디 두느냐"** 가 아니라 **"발행 측이 어느 인스턴스에 있든 올바른 인스턴스로 알림이 도달하느냐"** 다. 그래서 연결(`SseEmitter`)은 그대로 인스턴스 로컬에 두고, **알림 전달 경로만** Redis 를 경유시킨다.

---

## 3. 채택한 해법 — Redis Pub/Sub 브로드캐스트

### 채널 명명 규칙

```
sse:notify:{userId}
```

userId 별로 채널을 분리. 다른 사용자에게 전파되지 않게 자연스럽게 격리.

### 흐름

```
[알림 발행 측: app2 의 Kafka 컨슈머]
    sseNotificationService.sendNotification("X", item)
        ↓ ObjectMapper.writeValueAsString(item)
        ↓ redisTemplate.convertAndSend("sse:notify:X", json)
                ↓
            ┌───── Redis Pub/Sub ─────┐
            ↓                          ↓
[app1: PSUBSCRIBE sse:notify:*]   [app2: PSUBSCRIBE sse:notify:*]
    onMessage(msg) 호출                onMessage(msg) 호출
    emitters.get("X") → 있음           emitters.get("X") → null
    emitter.send(item)  ✅             조용히 return  ✅
```

### 핵심 설계 결정

| 결정 | 이유 |
|------|------|
| **연결 자체는 인스턴스 로컬 유지** | `SseEmitter` 는 직렬화·이전 불가. Redis 로 올리면 복잡도 폭증하고 실질 이득 없음 |
| **PSUBSCRIBE 패턴 구독** | 사용자 N 명마다 채널 만들고 그때그때 SUBSCRIBE 할 필요 없음. 와일드카드 한 번으로 끝 |
| **양쪽 모두 수신 → 자기 보유분만 send** | 발행 측은 emitter 가 어디 있는지 알 필요 없음 (위치 투명성). 인스턴스 N 대 추가에 코드 변경 없음 |
| **`DataAccessException` 흡수** | Redis 장애가 Kafka 컨슈머 예외 → 재시도 3회 → DLT 로 번지지 않게 차단 |

---

## 4. 코드 라인 단위 따라가기

### 4.1 `SseNotificationService` — 발행과 수신을 한 클래스에서

```java
@Service
public class SseNotificationService implements MessageListener {

    public static final String CHANNEL_PREFIX = "sse:notify:";

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public SseEmitter createConnection(String userId) {
        removeConnection(userId);  // 중복 연결 방지: 새 연결이 오면 기존 emitter complete
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);  // 30분 타임아웃
        emitters.put(userId, emitter);
        // onCompletion/onTimeout/onError 모두 emitters.remove(userId) — 끊긴 연결 정리
        return emitter;
    }

    // [발행] 어느 인스턴스에서 호출되든 OK
    public void sendNotification(String userId, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            redisTemplate.convertAndSend(CHANNEL_PREFIX + userId, json);
        } catch (JsonProcessingException e) {
            log.warn("SSE 알림 직렬화 실패: userId={}", userId, e);
        } catch (DataAccessException e) {
            // Redis 장애 시 흡수 → Kafka 컨슈머까지 예외 전파 차단
            log.warn("SSE 알림 Redis 발행 실패: userId={}, reason={}", userId, e.getMessage());
        }
    }

    // [수신] Redis 가 sse:notify:* 메시지 받을 때마다 모든 인스턴스에서 호출
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        if (!channel.startsWith(CHANNEL_PREFIX)) return;
        String userId = channel.substring(CHANNEL_PREFIX.length());

        SseEmitter emitter = emitters.get(userId);
        if (emitter == null) return;  // 다른 인스턴스가 담당 — 정상 흐름

        try {
            NotificationItemResponse item = objectMapper.readValue(
                message.getBody(), NotificationItemResponse.class);
            emitter.send(SseEmitter.event().name("notification").data(item));
        } catch (IOException e) {
            // emitter 전송 실패 → 맵에서 제거 + completeWithError
            emitters.remove(userId);
            try { emitter.completeWithError(e); } catch (Exception ex) { /* 무시 */ }
        }
    }
}
```

### 4.2 `SseRedisConfig` — 구독 등록

```java
@Configuration
public class SseRedisConfig {
    @Bean
    public RedisMessageListenerContainer sseRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            SseNotificationService sseNotificationService) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // PatternTopic("sse:notify:*") → Redis PSUBSCRIBE 명령
        // 앱 인스턴스마다 이 빈이 자동 생성되어 각자 같은 채널 구독
        container.addMessageListener(
            sseNotificationService,
            new PatternTopic(SseNotificationService.CHANNEL_PREFIX + "*")
        );
        return container;
    }
}
```

### 4.3 왜 `RedisConfig` 에 합치지 않았나

- `RedisConfig` 는 `RedisCacheManager` 등 캐시 설정 전담
- `SseRedisConfig` 는 Pub/Sub 리스너 전담 → 관심사 분리
- `RedisConfig` 에서 `SseNotificationService` 를 참조하면 `config` ↔ `notification` 패키지 간 의존 방향이 뒤섞임

---

## 5. 발행 측 호출 위치

| 호출처 | 시점 | 이벤트 종류 |
|--------|------|-------------|
| `PaymentNotificationService` | 결제 완료 후 | `PAYMENT_COMPLETED` |
| `SeatHoldEventConsumer` | 좌석 홀드 이벤트 컨슈밍 후 | `SEAT_HOLD_EXPIRED` 등 |
| `QueueProcessingScheduler` | 대기열 입장 허용 시 | `QUEUE_ENTRY_ALLOWED` |

발행 측은 전부 `sseNotificationService.sendNotification(userId, dto)` 한 줄만 호출. **어느 인스턴스에서 호출하는지 신경 쓸 필요 없음.**

---

## 6. 트레이드오프

### Pub/Sub 의 본성 — 메시지 영속성 없음

Redis Pub/Sub 은 **fire-and-forget**. 발행 시점에 구독자가 없으면 메시지는 사라진다. SSE 사용자가 일시적으로 끊긴 상태에서 알림이 발행되면 그 알림은 **유실됨**.

### 보완 — DB·Redis List 백업

| 자원 | 역할 |
|------|------|
| `notify:user:{userId}` (Redis List, 7일 TTL, 50건 LTRIM) | 알림 히스토리 — SSE 끊긴 사이에 발생한 알림을 클라이언트가 재연결 시 폴링으로 가져감 |
| `notifications` 테이블 (DB) | 장기 영속 — 알림 함 조회 |

즉 SSE Pub/Sub 은 *즉시 푸시 경로*고, *유실 보완은 List/DB* 가 담당한다. 이중화로 신뢰성 확보.

### 같은 사용자가 두 인스턴스에 동시 연결

`createConnection` 첫 줄이 `removeConnection(userId)` 라 같은 인스턴스에선 후행 연결이 선행 emitter 를 complete 시킨다. 하지만 **인스턴스 간**에는 통제 안 됨 — 사용자가 모바일은 app1, PC 는 app2 에 연결되면 양쪽 emitter 모두 살아 있고, Pub/Sub 본성상 둘 다 메시지 받음.

이건 의도된 동작 (multi-device 지원). 단일 인스턴스만 허용하고 싶으면 Redis 에 `sse:owner:{userId} = instanceId` 같은 키를 두고 새 연결 시 이전 인스턴스에 종료 신호를 보내는 메커니즘이 필요한데, 현재는 그 요구가 없어서 미구현.

### Redis 장애 시

- 발행 측: `DataAccessException` 흡수 → 해당 알림 1건 포기, 로그만 남김
- 구독 측: `RedisMessageListenerContainer` 자체가 Spring Data Redis 의 connection 재시도 메커니즘 사용 → Redis 복구 시 자동 재구독

흡수하지 않으면 → Kafka 컨슈머에서 예외 → 재시도 3회 → DLT 이동. SSE 푸시 실패 하나 때문에 정상 결제 이벤트가 DLT 로 흘러가는 과잉 반응이 발생함. 이걸 막는 의도적 설계.

---

## 7. 테스트 검증

`src/test/java/com/inyoung/ticketing/notification/SseNotificationMultiInstanceIntegrationTest.java`

같은 JVM 안에서 `SseNotificationService` 인스턴스 두 개 + 각자의 `RedisMessageListenerContainer` 를 띄워 "두 대의 앱 인스턴스" 시뮬레이션. Testcontainers Redis 사용.

| # | 시나리오 | 검증 포인트 |
|---|----------|-------------|
| 1 | **cross-instance broadcast** (핵심) | B(다른 인스턴스)에서 발행 → A 의 emitter 가 수신 |
| 2 | **broadcast 본성** | 같은 userId 가 A·B 양쪽 연결 → 두 emitter 모두 send |
| 3 | **사용자 격리** | A 에 user1 만 연결, B 에서 user2 publish → user1 emitter 호출 안 됨 |
| 4 | **no-op** | 어느 인스턴스에도 emitter 없음 → publish 해도 예외 없이 통과 |

Pub/Sub 비동기 특성 때문에 `Awaitility.await().atMost(3s).untilAsserted(...)` 로 폴링 검증. 격리 시나리오는 반대로 `during(1s)` 로 "1초 내내 호출 없음" 검증.

상세: [`test-code/16-sse-multi-instance-test.md`](../test-code/16-sse-multi-instance-test.md), 실행 결과: [`test-code/evidence/sse-multi-instance-test-result.md`](../test-code/evidence/sse-multi-instance-test-result.md).

---

## 8. 면접 질문 대비 한 줄 정리

| Q | A 핵심 |
|---|--------|
| SSE 같은 stateful 연결을 다중 인스턴스에서 어떻게? | 연결은 인스턴스 로컬, **알림 전달 경로만** Redis Pub/Sub 으로 우회 |
| nginx Sticky Session 으로 안 풀고 왜 Pub/Sub? | Sticky 는 요청 라우팅만 고정. Kafka 컨슈머 실행 인스턴스는 통제 못 함 |
| SSE 연결 자체를 Redis 에 올리지 않은 이유? | `SseEmitter` 직렬화 불가 + 옮긴들 long-lived TCP socket 이전 불가. 본질이 아닌 곳에 복잡도 들이지 않음 |
| Pub/Sub 메시지 유실은? | fire-and-forget 본성 → 보완으로 `notify:user:{userId}` List + DB 영속 이중화 |
| Redis 장애 시? | `DataAccessException` 흡수 → Kafka 컨슈머 예외 전파/DLT 연쇄 차단. SSE 는 best-effort |
| 같은 사용자 동시 다중 연결? | broadcast 본성상 양쪽 모두 수신. multi-device 지원. 단일 연결 강제는 미구현 |

---

## 9. ADR 참조

원본 설계 결정: [`docs/ticketing-portfolio.md`](../docs/ticketing-portfolio.md) **ADR-6**.
