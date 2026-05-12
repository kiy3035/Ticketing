# 트러블슈팅 로그

개발 중 겪은 이슈와 해결 과정. 면접에서 "개발 중 어떤 문제를 겪었고 어떻게 해결했나요?" 질문 대비.

---

## #1. Kafka Consumer 가 메시지를 못 읽음

**증상**: Producer는 JSON으로 보냈는데 Consumer에서 역직렬화 실패

**원인**: Producer는 `JsonSerializer`, Consumer는 `StringDeserializer` 사용 → 타입 헤더(`__TypeId__`) 불일치

**해결**: Consumer도 `JsonDeserializer` 통일 (`KafkaConfig`)
```java
JsonDeserializer<SeatHoldEvent> deserializer = new JsonDeserializer<>(SeatHoldEvent.class, objectMapper);
deserializer.setRemoveTypeHeaders(true);                    // Producer 타입 헤더 의존 제거
deserializer.addTrustedPackages("com.inyoung.ticketing.*"); // 보안 화이트리스트
deserializer.setUseTypeMapperForKey(false);                 // key는 String
```

**재발 방지**: 새 Kafka 토픽 추가 시 `KafkaConfig` 패턴 따르기 (Producer/Consumer Factory + 동일 ObjectMapper 빈).

---

## #2. `ddl-auto=update` 에서 Flyway 전환 시 컬럼 누락

**증상**: `BaseEntity` 추가 후 `updated_at` 컬럼이 없어서 앱 기동 실패 (`SchemaValidationException`)

**원인**: Flyway V1이 새 스키마를 만들지만 기존 DB에는 `updated_at` 이 없음. `ddl-auto=validate` 가 검증에서 실패.

**해결**:
- `baseline-on-migrate=true`, `baseline-version=0` 설정으로 기존 DB에 baseline 생성
- V1에 `IF NOT EXISTS` 사용
- V3에서 프로시저 + `IF NOT EXISTS` 컬럼 체크 패턴으로 누락 컬럼 보정

**재발 방지**: BaseEntity 같은 공통 컬럼 추가 시 반드시 마이그레이션 같이 작성.

---

## #3. 예약 확정 직후 Kafka 메시지가 안 보임 (Outbox 패턴 도입 후)

**증상**: DB에 예약은 생겼는데 `ticketing.seat-hold-events` 토픽에 `RESERVATION_CONFIRMED` 메시지가 즉시 안 보인다.

**원인**: 해당 이벤트는 **`ReservationConfirmedEventListener` 가 직접 발행하지 않는다.** `kafka_outbox` 테이블에 INSERT 만 하고, **`KafkaOutboxPublishScheduler`** 가 주기(`ticketing.outbox.publish-interval-ms=500ms`)로 읽어 전송.

**확인 방법**:
- `kafka_outbox` 테이블에 `PENDING` 행 존재 여부
- 스케줄러 로그 + `ticketing_outbox_published_total` 메트릭
- 브로커 지연 시 `status='FAILED'` 행 여부

**재발 방지**: 새로운 Kafka 발행을 outbox로 보낼지 직접 send로 보낼지 결정 시 "DB 커밋과 묶여야 하는가?"를 기준으로.

---

## #4. `@Scheduled` 메서드의 `@Transactional` 자기호출 문제

**증상**: outbox 스케줄러가 도는데 트랜잭션 컨텍스트가 적용 안 됨 (Lazy 로딩 실패, `delete()` 가 미반영 등)

**원인**: `@Scheduled` 메서드는 Spring AOP 프록시 밖에서 호출되므로, 같은 클래스 내부의 `@Transactional` 어노테이션이 효과 없음 (자기호출 함정).

**해결**: `KafkaOutboxPublishScheduler` 에서 `TransactionTemplate.executeWithoutResult(...)` 로 명시적 트랜잭션 열기:
```java
@Scheduled(fixedDelayString = "...")
public void publishPending() {
    Optional<String> lockToken = lockService.tryLock(LOCK_KEY, LOCK_TTL);
    if (lockToken.isEmpty()) return;
    try {
        transactionTemplate.executeWithoutResult(status -> processPendingBatch());
    } finally {
        lockService.unlock(LOCK_KEY, lockToken.get());
    }
}
```

**재발 방지**: 스케줄러에서 트랜잭션이 필요하면 항상 `TransactionTemplate` 또는 다른 빈으로 분리.

---

## #5. Kafka 헬스체크가 부하 시 60초 타임아웃 → readiness 죽음

**증상**: 부하 테스트 중 `/actuator/health` 가 60초 멈춤 → nginx upstream으로 들어온 요청이 timeout → passive HC 누적으로 인스턴스 격리 → 트래픽 끊김

**원인**: Spring Boot 기본 Kafka HealthIndicator 가 브로커 metadata 조회 시 timeout 60초.

**해결**: `application.properties` 에서 Kafka 헬스 명시적 비활성화 + 자체 묶음 헬스 사용
```properties
management.health.kafka.enabled=false
```
대신 `TicketingDatastoresHealthIndicator` 가 DB + Redis 만 묶어서 평가.

**재발 방지**: 외부 의존성 헬스체크 추가 시 timeout/circuit 동작 검토.

---

## #6. Saga 보상에서 outer 트랜잭션 롤백과 함께 보상도 사라짐

**증상**: 결제 완료 시 예약 확정 실패하면 보상 코드(포인트 환불 + 결제 CANCELED)도 같이 롤백되어 결국 포인트만 빠진 상태가 남음.

**원인**: 보상 메서드를 같은 트랜잭션 (`@Transactional` 기본 전파 `REQUIRED`)에서 실행했기 때문.

**해결**: `PaymentCompensationService.compensateAfterReservationFailure` 를 `@Transactional(propagation = REQUIRES_NEW)` 로 분리. outer 트랜잭션과 독립 커밋.

**재발 방지**: 보상 트랜잭션·감사 로그 등 "outer 가 롤백되어도 살아남아야 하는" 작업은 명시적으로 `REQUIRES_NEW`.

---

## #7. RDS `max_connections` 초과 우려 (HikariCP 튜닝)

**증상**: 부하 테스트 시 HikariCP `maximum-pool-size` 를 너무 크게 잡아 RDS `max_connections` 한계 초과 가능성.

**계산**: 앱 서버 2대 × `maximum-pool-size=30` = 최대 60개 상시 점유 가능 → RDS 인스턴스 한계 확인 필요

**해결**: `minimum-idle=5` 로 평시 점유 최소화. 부하 시에만 `maximum-pool-size` 까지 동적 확장.
```properties
spring.datasource.hikari.maximum-pool-size=30
spring.datasource.hikari.minimum-idle=5
```

**재발 방지**: 인스턴스 수 × max-pool-size + 다른 클라이언트(관리도구 등) 합이 RDS 한계의 70% 이내가 되도록.

---

## 형식
> 새 이슈는 **증상 → 원인 → 해결 → 재발 방지** 형식으로 추가한다.
