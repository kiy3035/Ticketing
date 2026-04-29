# Virtual Thread (Java 21 Loom) 가이드

## Virtual Thread란?

Java 21에서 정식 도입된 **경량 스레드**. OS가 관리하는 Platform Thread와 달리, JVM이 직접 스케줄링한다.

### 핵심 차이

```
Platform Thread (기존):
  - OS 커널이 관리
  - 스택: 기본 1MB 고정
  - 생성 비용: ~1ms
  - 200개 → 스택만 ~200MB
  - 컨텍스트 스위칭: 커널 모드 전환 필요

Virtual Thread (Java 21):
  - JVM이 관리, OS 스레드(carrier thread) 위에서 실행
  - 스택: 수KB (필요 시 자동 확장)
  - 생성 비용: ~1μs (1000배 빠름)
  - 10,000개 만들어도 수십MB
  - I/O 대기 시 carrier thread를 반납 → 다른 Virtual Thread가 사용
```

### 비유로 이해하기
- **Platform Thread** = 택시 1대에 승객 1명. 승객이 병원에서 기다려도 택시는 대기.
- **Virtual Thread** = 택시가 승객을 내려놓고 다른 승객 태움. 병원 끝나면 다시 태움.
- **carrier thread** = 택시. `ForkJoinPool`에서 관리 (기본 vCPU 개수만큼).

---

## 이 프로젝트에서 적용한 곳

### 1. Tomcat 요청 처리 (가장 큰 효과)

**설정 1줄** (`application.properties`):
```properties
spring.threads.virtual.enabled=true
```

**Spring Boot 3.2+ 에서 이 한 줄이 하는 것**:
- `TomcatProtocolHandlerCustomizer` 활성화
- Tomcat의 기본 `ThreadPoolExecutor`(max 200) 대신 `VirtualThreadExecutor` 사용
- 모든 HTTP 요청 핸들러(`@Controller`, `@RestController`)가 Virtual Thread에서 실행

**왜 효과가 큰가**:
```
홀드 API 1건의 시간 분해:
  ┌──────────────────────────────────────────────────┐
  │ Redis 락 획득     │██░░░░░░░░│  2ms (I/O 대기)     │
  │ Redis Lua 홀드    │█░░░░░░░░░│  1ms (I/O 대기)     │
  │ Kafka(HOLD_CREATED)│█████░░░░░│  5ms (I/O 대기)     │
  │ CPU 연산          │░░░░░░░░░░│  0.1ms              │
  └──────────────────────────────────────────────────┘
  → I/O 대기 99%. Platform Thread는 99% 시간을 "점유만" 하고 있었다.
```

**기존**: Thread 200개 → 동시 요청 200개 한계
**변경**: Virtual Thread → carrier thread는 vCPU 2개만 있어도 수천 동시 요청 처리
(실제 병목은 DB 커넥션 풀이나 Redis 커넥션 수로 넘어감)

### 2. `HoldCleanupScheduler` — 만료 홀드 병렬 정리

**변경 후**:
```java
private void doCleanupExpiredHolds() {
    int batchSize = properties.getHold().getCleanupBatchSize();
    List<HoldPayload> expired = holdStore.findExpiredHolds(Instant.now(), batchSize);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (HoldPayload payload : expired) {
            executor.submit(() -> {
                holdStore.releaseByPayload(payload.info(), payload.payload());
                holdReleaseMetrics.recordReleased("timeout");
                eventPublisher.publish(SeatHoldEventType.HOLD_EXPIRED, payload.info());
                seatService.evictQueueStatusAvailableSeats(payload.info().getConcertId());
            });
        }
    }
    // try-with-resources: ExecutorService.close() 가 모든 태스크 완료 대기
}
```

**`Executors.newVirtualThreadPerTaskExecutor()` 동작**:
- submit()할 때마다 새 Virtual Thread 생성
- 생성 비용 ~1μs → 200개 만들어도 ~0.2ms
- try-with-resources의 close()에서 모든 VT 종료를 기다림
- 풀 크기 설정 불필요 (VT는 풀링하지 않는다)

**효과**: 200건 × 8ms 순차 = 1.6초 → 병렬 ~50ms (Redis/Kafka 커넥션이 자연 조절)

### 3. `RefundForCancelledConcertScheduler` — 환불 병렬 처리

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (Payment payment : chunk) {
        executor.submit(() -> {
            try {
                boolean done = paymentService.refundCompletedPaymentForCancelledConcert(payment.getId());
                if (done) totalRefunded.incrementAndGet();
            } catch (Exception e) {
                totalFailed.incrementAndGet();
                log.warn("Refund failed for paymentId={}, ...", payment.getId(), e.getMessage());
            }
        });
    }
}
```

**카운터를 `AtomicInteger`로**: 여러 VT 에서 동시 접근 시 Lost Update 방지 (CAS).

**DB 커넥션 풀이 자연 조절하는 원리**:
- VT 50개가 동시 DB 접근 시도
- HikariCP 커넥션 풀 max=30 → 30개만 실제 연결, 나머지 20개 대기
- 대기 중 VT는 carrier thread 반납 → 다른 작업 처리 가능
- Platform Thread 50개였다면 20개 OS 스레드가 아무것도 안 하며 점유

### 4. Kafka Consumer 리스너 (`KafkaConfig`)

```java
private SimpleAsyncTaskExecutor virtualThreadExecutor(String prefix) {
    SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor(prefix);
    executor.setVirtualThreads(true);  // Spring 6.1+ API
    return executor;
}

// 두 ListenerContainerFactory 모두 적용
factory.getContainerProperties().setListenerTaskExecutor(virtualThreadExecutor("kafka-seat-hold-"));
factory.getContainerProperties().setListenerTaskExecutor(virtualThreadExecutor("kafka-payment-"));
```

**왜 적용했나**:
- `PaymentCompleteEventConsumer` → 이메일/SMS 발송 (SMTP I/O ~100ms)
- `SeatHoldEventConsumer` → DB 조회/SSE 발송
- 이 I/O 대기 동안 Consumer Platform Thread 점유 시 다음 이벤트 처리 지연

---

## 적용하지 않은 곳과 이유

### `@Scheduled` 트리거 스레드
```java
@Scheduled(fixedDelayString = "...")
public void cleanupExpiredHolds() {
    // ← 이 메서드를 "호출"하는 스레드는 Spring TaskScheduler의 Platform Thread
    //    호출 자체가 가볍고, 분산 락 획득(Redis SETNX) 1번이 전부
    //    내부의 doCleanupExpiredHolds()에서 Virtual Thread를 사용
}
```
트리거 스레드를 VT로 바꿔도 이득 없음. 60초마다 1번 실행, 락 획득 1회뿐.

### `QueueProcessingScheduler`
```java
for (Concert concert : concerts) {
    for (String token : topTokens) {
        queueService.isAllowed(token);   // Redis GET ~0.5ms
        queueService.allowEntry(token);  // Redis SET ~0.5ms
    }
}
```
- 2초마다 실행, 콘서트 수 적음, 각 호출이 1ms 미만
- VT 생성·스케줄링 오버헤드가 I/O 대기보다 클 수 있음
- 순차 실행이 더 예측 가능하고 디버깅도 쉬움

### Lettuce(Redis) Netty I/O 스레드
- Lettuce는 내부적으로 Netty `EventLoopGroup`에서 non-blocking I/O
- `redisTemplate.opsForValue().get(key)` 호출 시:
  1. VT(호출 측)가 Lettuce에 명령 전달
  2. Netty I/O 스레드가 Redis에 명령 전송 (우리가 제어 안 함)
  3. 응답 도착 시 VT 깨어남
- Netty I/O 스레드를 VT로 바꾸면 오히려 성능 저하 (epoll 이벤트 루프가 깨짐)

### Kafka Producer 내부 I/O 스레드
- Kafka Producer는 `sender` 스레드 1개가 배치 전송
- 라이브러리가 생성·관리하므로 직접 제어 불가

---

## Pinning (고정) 문제

### 문제
```java
synchronized (lock) {
    jdbc.executeQuery("SELECT ...");  // Virtual Thread가 carrier thread에 pin됨!
    // carrier thread를 반납하지 못함 → Platform Thread와 동일한 문제
}
```

### 이 프로젝트에서 안전한 이유
1. **직접 `synchronized` 사용 없음** — 모든 동시성 제어가 Redis 분산 락 또는 DB 비관적 락
2. **MySQL Connector/J 9.x** — 내부 `synchronized` → `ReentrantLock` 으로 교체 완료
3. **Lettuce** — Netty 기반, `synchronized` 미사용
4. **Spring Framework 6.1** — 내부 락을 `ReentrantLock` 으로 전환

### 확인 방법
```bash
# JVM 옵션으로 pinning 감지 (개발 환경 테스트 시)
-Djdk.tracePinnedThreads=short
```
pinning 발생 시 로그에 스택 트레이스 출력.

---

## 면접 대비 Q&A

### Q: Virtual Thread를 왜 사용했나요?
> 이 프로젝트는 요청 1건당 Redis 락, DB 트랜잭션, Kafka 발행 등 I/O 대기가 99%입니다.
> 기존 Platform Thread 200개 풀에서는 200명이 동시에 I/O 대기만 해도 스레드가 고갈됩니다.
> Virtual Thread를 적용하면 I/O 대기 중 carrier thread를 반납하므로, vCPU 2개인 t3a.small에서도 수천 동시 요청 처리 가능합니다.

### Q: 왜 전부 다 Virtual Thread로 안 바꿨나요?
> "I/O 대기를 저렴하게" 만드는 기술이라, CPU 연산 위주이거나 라이브러리가 자체 스레드 관리하는 곳(Netty, Kafka sender)에는 이득 없거나 오히려 해롭습니다.
> 예를 들어 `QueueProcessingScheduler` 는 Redis 호출이 0.5ms 단위라 VT 오버헤드가 더 클 수 있어 순차 실행을 유지했습니다.

### Q: WebFlux와 비교하면?
> WebFlux는 전체 코드를 Mono/Flux 기반으로 재작성해야 하고, JPA도 R2DBC로 바꿔야 합니다.
> Virtual Thread는 기존 동기 코드(JPA, `@Transactional`) 그대로 두면서 I/O 효율만 개선합니다.
> "코드 복잡도 vs 성능 이득" 트레이드오프에서 VT가 이 프로젝트에 더 적합합니다.

### Q: 실제 성능 차이를 측정했나요?
> k6 부하 테스트로 VT 적용 전후 비교 가능. 주요 관측 포인트:
> - `http_reqs` (초당 처리량)
> - `http_req_duration` p95 (스레드 고갈 시점의 응답 시간 급등 여부)
> - Grafana `jvm_threads_live_threads` 메트릭 (Platform Thread 수 감소 확인)

### Q: Pinning 문제는 없나요?
> `synchronized` 직접 사용 안 하고, MySQL Connector/J 9.x + Spring 6.1 + Lettuce 모두 내부 락이 `ReentrantLock`으로 교체된 버전이라 발생 가능성이 매우 낮습니다.
> 개발 환경에서 `-Djdk.tracePinnedThreads=short` 로 검증할 수 있습니다.

---

## 참고 자료
- [JEP 444: Virtual Threads (Java 21)](https://openjdk.org/jeps/444)
- [Spring Boot 3.2 — Virtual Threads Support](https://spring.io/blog/2023/09/09/all-together-now-spring-boot-3-2-graalvm-native-images-java-21-and-virtual)
