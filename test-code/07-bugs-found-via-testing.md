# 07. 테스트로 발견·수정한 버그

> 이 문서는 테스트 작성·실행 과정에서 **실제로 발견하고 수정한 버그**를 기록한다.
> 면접에서 "테스트 코드가 실제로 도움이 됐나요?" 라는 질문에 대한 근거 자료다.

---

## Bug 1. Testcontainers + Spring Context Cache mismatch (테스트 실행으로 발견)

### 증상
- 통합 테스트를 **개별**로 실행하면 모두 통과
- `./gradlew test`로 **전체** 실행하면 `ConnectException`, `RedisCommandTimeoutException` 발생

```
Caused by: java.sql.SQLTransientConnectionException
    Caused by: com.mysql.cj.exceptions.CJCommunicationsException
        Caused by: java.net.ConnectException  ← MySQL 컨테이너에 연결 자체가 안 됨
```

### 원인
`@Testcontainers` + `@Container` 어노테이션 조합은 **테스트 클래스마다 컨테이너 lifecycle을 별도로 관리**한다.
`TicketingApplicationTests`가 끝나면 컨테이너가 종료되고 새 포트로 재시작된다.
그런데 Spring은 `@DynamicPropertySource`로 등록된 프로퍼티 기반으로 Context를 캐싱해두기 때문에,
**다음 테스트 클래스가 새 포트의 컨테이너를 써야 하는데 캐싱된 Context는 이전 포트를 참조**한다.

```
TicketingApplicationTests 실행
  → MySQL 컨테이너 port:3306 기동
  → Spring Context 생성 (datasource.url = jdbc:mysql://...:3306/...)
  → 테스트 완료, @Container 어노테이션에 의해 컨테이너 종료

SeatHoldConcurrencyTest 실행
  → MySQL 컨테이너 port:3307 로 재기동
  → Spring Context Cache 히트 → 이전 Context(port:3306) 재사용
  → java.net.ConnectException ← 3306은 이미 없음
```

### 수정
`@Testcontainers` + `@Container`를 제거하고 **static 초기화 블록으로 컨테이너를 JVM 레벨 싱글턴**으로 만들었다.
컨테이너는 JVM 최초 로딩 시 한 번만 시작되고, JVM 종료 시까지 같은 포트를 유지한다.
모든 테스트 클래스가 동일한 컨테이너 인스턴스·포트를 공유하므로 Context Cache와 충돌이 없다.

```java
// Before: 클래스마다 lifecycle 별도
@Testcontainers
abstract class IntegrationTestBase {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");
}

// After: JVM 싱글턴
abstract class IntegrationTestBase {
    static final MySQLContainer<?> mysql;
    static {
        mysql = new MySQLContainer<>("mysql:8.0")...;
        mysql.start();  // JVM 로딩 시 한 번만 실행
    }
}
```

### 시사점
- 통합 테스트가 전체 실행 시에만 간헐적으로 깨지는 문제는 **환경 의존성 버그**일 가능성이 높다
- "개별 테스트 통과 = 전체 테스트 통과" 가 아니다
- Testcontainers + Spring Context Cache 조합은 lifecycle 관리에 주의가 필요하다

---

## Bug 2. JWT Refresh 회전의 비원자 트랜잭션 (코드 리뷰로 발견)

### 위치
`JwtAuthenticationService.authenticate()` — Case 3 (Access 유효, Refresh 만료)

### 증상
단일 서버에서는 증상 없음. **서버 2대(스케일아웃) 환경에서만 발생**하는 잠재적 버그.

### 원인
Refresh 만료 시 "구 Refresh 폐기 → 새 Refresh 저장" 을 **두 개의 별도 트랜잭션**으로 처리하고 있었다.

```java
// Before: TX1과 TX2 사이에 gap이 존재
refreshTokenPersistenceService.revokeByJti(oldJti);   // TX1 커밋
refreshTokenPersistenceService.saveNew(newJti, ...);   // TX2 커밋
```

**시나리오 A — 서버 장애**:
TX1 성공(구 토큰 revoked) → TX2 실패(새 토큰 없음) → 사용자 강제 로그아웃

**시나리오 B — 스케일아웃 race condition**:
```
서버1: TX1 커밋 (oldJti revoked 상태로 DB에 존재)
             ↓ gap
서버2: 같은 oldJti로 요청 수신
     → detectReuseOfRevokedRefresh() 실행
     → "이미 revoked된 jti 재사용 = 탈취 의심" 판단
     → family 전체 무효화 → 정상 사용자 강제 로그아웃
             ↓
서버1: TX2 커밋 (이미 family가 무효화된 상태)
```

### 수정
두 작업을 `replaceRefresh()` **단일 트랜잭션**으로 묶었다.

```java
// After: 단일 트랜잭션으로 원자성 보장
@Transactional
public void replaceRefresh(String oldJti, String newJti, String username, LocalDateTime newExpiresAt) {
    String familyId = refreshTokenRepository.findByJti(oldJti)
        .map(rt -> { rt.setRevoked(true); return rt.getFamilyId(); })
        .orElseGet(() -> jwtTokenService.newJti());
    saveNew(newJti, username, newExpiresAt, familyId);
}
```

TX1과 TX2 사이의 gap이 사라지므로 서버 간 race condition이 발생하지 않는다.

### 시사점
- **단일 서버에서 통과하는 테스트가 분산 환경의 안전성을 보장하지 않는다**
- JWT처럼 상태 변경이 복잡한 도메인은 트랜잭션 경계를 명시적으로 설계해야 한다
- "2대로 스케일아웃하면 어떻게 되는가?" 라는 질문을 코드 리뷰 단계에서 해야 한다

---

## Bug 3. nginx access_log에 JWT 토큰 평문 기록 (코드 리뷰로 발견)

### 위치
`nginx/nginx.conf` — SSE 경로

### 원인
브라우저 `EventSource`는 커스텀 헤더를 지원하지 않아, SSE 경로에 한해 쿼리 파라미터로 JWT를 전달한다.

```
GET /api/notifications/stream?accessToken=eyJhbGc...&refreshToken=eyJhbGc...
```

이 URL이 nginx `access_log`에 **평문 그대로 기록**된다.
nginx 로그 접근 권한이 있는 사람이 유효한 JWT 토큰을 탈취할 수 있다.

### 수정
nginx `map` 지시어로 해당 경로의 쿼리 파라미터를 마스킹했다.

```nginx
map $request_uri $loggable_uri {
    ~^/api/notifications/stream  "/api/notifications/stream?[TOKEN_MASKED]";
    default                       $request_uri;
}
log_format main '... "$request_method $loggable_uri ..."';
```

---

---

## Bug 4. Kafka 컨슈머 멱등성 누락 (코드 리뷰로 발견)

### 위치
`PaymentCompleteEventConsumer.handlePaymentComplete()`

### 원인
Kafka 는 at-least-once 전달이라 같은 메시지가 두 번 이상 컨슈머에 도달할 수 있다.
하지만 기존 컨슈머는 멱등성 체크 없이 매번 알림 발송을 호출했다.

```java
// Before — 중복 메시지 = 알림 중복 발송
@KafkaListener(topics = "ticketing.payment-complete", ...)
public void handlePaymentComplete(PaymentCompleteEvent event) {
    paymentNotificationService.notifyPaymentComplete(...);  // 무방비
}
```

### 위험
- 컨슈머가 메시지 처리 후 offset commit 직전에 크래시 → 재시작 시 같은 메시지 재수신
- 결과: 사용자에게 결제 완료 메일·SMS 가 두 번 발송 → UX 사고, 신뢰 손상

### 수정
`paymentKey` 를 멱등성 키로 Redis 에 저장. SET NX 로 동시 중복 처리도 차단.

```java
String idempotencyKey = "kafka:payment-complete:" + event.getPaymentKey();
if (!idempotencyService.acquireKey(idempotencyKey, Duration.ofHours(24))) {
    return;  // 이미 처리됨 — 중복 알림 차단
}
try {
    paymentNotificationService.notifyPaymentComplete(...);
    idempotencyService.saveResult(idempotencyKey, "DONE", Duration.ofHours(24));
} catch (RuntimeException e) {
    idempotencyService.releaseKey(idempotencyKey);  // 재시도 가능하게 키 해제
    throw e;
}
```

회귀 방지를 위해 `PaymentCompleteEventConsumerIntegrationTest` 에서 4 시나리오 검증.

### 시사점
- Kafka 의 **at-least-once** 특성을 늘 의식해야 한다
- 컨슈머는 **반드시 멱등**해야 운영 사고를 막는다
- Redis SET NX 의 원자성은 Mock 으로 검증 불가 — Testcontainers 통합 테스트 필수

---

## 전체 요약

| # | 버그 | 발견 방법 | 발생 환경 |
|---|------|----------|---------|
| 1 | Testcontainers + Spring Context Cache mismatch | 전체 테스트 실행 | CI / 전체 테스트 |
| 2 | JWT Refresh 비원자 트랜잭션 | 코드 리뷰 (스케일아웃 관점) | 서버 2대 이상 |
| 3 | nginx 로그 토큰 노출 | 코드 리뷰 (보안 관점) | 운영 환경 |
| 4 | Kafka 컨슈머 멱등성 누락 (PaymentCompleteEventConsumer) | 코드 리뷰 (분산 메시징 관점) | Kafka 재시도·리밸런스 시 |
| 5 | Kafka 컨슈머 멱등성 누락 (SeatHoldEventConsumer) | 연쇄 점검 (버그 하나 발견 → 유사 코드 전체 확인) | Kafka 재시도·리밸런스 시 |
