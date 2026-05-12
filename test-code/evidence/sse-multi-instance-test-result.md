# SSE 다중 인스턴스 브로드캐스트 통합 테스트 — 실행 결과

> 실행일: 2026-05-12
> 환경: Windows 11 / bash / Docker Desktop (Testcontainers 자동 기동)
> 명령어: `./gradlew test --tests "SseNotificationMultiInstanceIntegrationTest" --rerun-tasks`

---

## 결과 요약

![SSE 다중 인스턴스 테스트 결과](../images/sse%20다중인스턴스%20테스트%20결과.png)


| 항목 | 결과 |
|------|------|
| 테스트 수 | **4개** |
| 통과 | **4개** |
| 실패 | **0개** |
| 빌드 결과 | **BUILD SUCCESSFUL** |
| 전체 실행 시간 | **1분 9초** (Testcontainers MySQL/Redis/Kafka 기동 포함) |

> Testcontainers 가 MySQL 8.0 + Redis 7 + Kafka 컨테이너를 자동으로 기동·종료함.
> 운영 서버·인프라 서버에 연결하지 않고 독립 실행.

---

## 테스트별 통과 내역

| # | 시나리오 | 결과 |
|---|----------|------|
| 1 | B(다른 인스턴스)에서 발행한 알림을 A(연결 보유 인스턴스)가 수신해 emitter 에 전달한다 | ✅ PASSED |
| 2 | 같은 userId 가 A·B 양쪽에 연결돼 있으면 두 emitter 모두 메시지를 수신한다 | ✅ PASSED |
| 3 | user2 에게 보낸 알림이 user1 emitter 로 전달되지 않는다 (채널 격리) | ✅ PASSED |
| 4 | 어느 인스턴스에도 emitter 가 없는 사용자에게 publish 해도 예외 없이 통과한다 | ✅ PASSED |

---

## 실제 콘솔 출력

```
SseNotificationMultiInstanceIntegrationTest > B(다른 인스턴스)에서 발행한 알림을 A(연결 보유 인스턴스)가 수신해 emitter 에 전달한다 PASSED
SseNotificationMultiInstanceIntegrationTest > 같은 userId 가 A·B 양쪽에 연결돼 있으면 두 emitter 모두 메시지를 수신한다 PASSED
SseNotificationMultiInstanceIntegrationTest > 어느 인스턴스에도 emitter 가 없는 사용자에게 publish 해도 예외 없이 통과한다 PASSED
SseNotificationMultiInstanceIntegrationTest > user2 에게 보낸 알림이 user1 emitter 로 전달되지 않는다 (채널 격리) PASSED

BUILD SUCCESSFUL in 1m 9s
```

---

## 검증 내용 상세

### 시나리오 1 — cross-instance broadcast (핵심)

**무엇을 증명했나**: 운영의 가장 흔한 시나리오 — "사용자가 app1 에 SSE 연결, 결제 Kafka 이벤트가 app2 에서 컨슈밍됨" 상황에서 알림이 누락되지 않음을 증명.

- **given**: instanceA 의 emitters 맵에 `userId` 와 spy emitter 등록
- **when**: instanceB 에서 `instanceB.sendNotification(userId, item)` 호출 → Redis `PUBLISH sse:notify:{userId}`
- **then**: Awaitility 가 3초 안에 `verify(spyEmitterOnA, atLeastOnce()).send(...)` 를 통과하는지 폴링
- **결과**: A 의 emitter 가 메시지를 수신, send 호출 검증됨

> 이 테스트가 PASS 한다는 건 = "발행 인스턴스 ≠ 구독 인스턴스" 환경에서 broadcast 가 동작함을 코드로 증명한 것.

### 시나리오 2 — broadcast 본성 (양쪽 동시 연결)

**무엇을 증명했나**: Pub/Sub 의 본질인 "PUBLISH 는 모든 구독자에게 전달된다" 는 점.

- **given**: 같은 userId 가 instanceA·B 양쪽에 emitter 등록 (모바일 + PC 동시 접속 시나리오)
- **when**: A 에서 publish (B 에서 publish 해도 결과 동일)
- **then**: 두 emitter 모두 send 호출됨

### 시나리오 3 — 사용자 격리

**무엇을 증명했나**: 채널 prefix `sse:notify:{userId}` 가 사용자별로 분리되어 있어 다른 사용자의 메시지가 새지 않음.

- **given**: A 에 user1 emitter 만 등록 (user2 는 어디에도 없음)
- **when**: B 에서 user2 에게 publish
- **then**: `Awaitility.during(1s)` 로 1초 동안 user1 emitter 의 send 가 한 번도 호출되지 않음 검증

### 시나리오 4 — no-op (회복력)

**무엇을 증명했나**: 사용자가 SSE 연결 끊은 직후 발생한 알림이 컨슈머를 죽이지 않음.

- **given**: 두 인스턴스 모두 emitter 없음
- **when**: 양쪽에서 ghost 사용자에게 publish
- **then**: 예외 없이 정상 리턴 (subscriber side 에서도 emitter == null 일 때 조용히 return)

---

## 인프라 구성 (Testcontainers 자동 기동)

| 컨테이너 | 이미지 | 용도 |
|----------|--------|------|
| MySQL | `mysql:8.0` | 컨텍스트 로딩용 (이 테스트에선 직접 사용 안 함) |
| Redis | `redis:7-alpine` | **Pub/Sub PUBLISH/PSUBSCRIBE 채널 — 실제 검증 대상** |
| Kafka | `confluentinc/cp-kafka:7.5.0` | 컨텍스트 로딩용 |

---

## 핵심 기법

### 1. 같은 JVM 안에서 "두 대의 앱 인스턴스" 시뮬레이션

`@BeforeEach` 에서 `instanceA` (Spring 빈) 외에 `instanceB = new SseNotificationService(...)` 를 수동 생성하고, **별도의 `RedisMessageListenerContainer`** 로 같은 채널을 PSUBSCRIBE 시킨다. 두 JVM 을 띄우는 대신 두 listener container 만으로 "발행 인스턴스 ≠ 구독 인스턴스" 본질을 동등하게 검증.

### 2. spy emitter + reflection 직접 주입

`createConnection()` 으로 만들면 진짜 `SseEmitter` 가 생성돼 `verify` 가 어렵다. mockito spy 로 감싼 emitter 를 reflection 으로 private `emitters` 맵에 직접 put. 운영 코드는 건드리지 않고 테스트 전용 헬퍼.

### 3. Awaitility 비동기 검증

Redis Pub/Sub 은 publish → broker → subscriber 콜백 사이에 ms 단위 지연이 있다. `Thread.sleep` 대신 `Awaitility.await().atMost(3s).untilAsserted(...)` 로 폴링. 시나리오 3 은 반대로 `during(1s)` 로 "1초 내내 호출 없음" 을 검증.

---

## 면접 활용 포인트

> "SSE 같은 stateful 한 자원을 다중 인스턴스에서 어떻게 다뤘냐는 질문에, **알림 전달 경로만 Redis Pub/Sub 으로 우회**하는 최소 침습 해법으로 풀었습니다.
> 발행 인스턴스와 SSE 연결 인스턴스가 달라도 정상 전달됨을 Testcontainers Redis 기반으로 4 시나리오에 걸쳐 검증했고, 격리·broadcast·no-op 모두 PASS 됩니다.
> nginx Sticky Session 으로는 Kafka 컨슈머 인스턴스를 제어할 수 없어 본질이 해결되지 않는다는 점도 코드 주석과 ADR 에 명시했습니다."
