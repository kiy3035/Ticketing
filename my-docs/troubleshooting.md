# 트러블슈팅 로그

개발 중 겪은 이슈와 해결 과정을 기록한다.
면접에서 "개발 중 어떤 문제를 겪었고 어떻게 해결했나요?" 질문 대비.

---

## 이슈 목록

### #1. Spring Session + Redis 직렬화 오류
**증상**: 로그인 후 세션에 사용자 정보 저장 시 `IllegalStateException`
**원인**: Spring Session과 Spring Security의 Jackson 모듈 버전 불일치
**해결**: `SessionConfig`에서 `SecurityJackson2Modules`를 명시적으로 등록
```java
ObjectMapper objectMapper = new ObjectMapper()
    .registerModule(new JavaTimeModule())
    .registerModules(SecurityJackson2Modules.getModules(getClass().getClassLoader()));
```

### #2. Kafka Consumer가 메시지를 못 읽음
**증상**: Producer에서 JSON으로 보냈는데 Consumer에서 역직렬화 실패
**원인**: Producer는 `JsonSerializer`, Consumer는 `StringDeserializer` 사용 → 타입 헤더 불일치
**해결**: Consumer도 `JsonDeserializer` 통일 + `setRemoveTypeHeaders(true)`, `addTrustedPackages("com.inyoung.ticketing.*")` (과도한 와일드카드 지양)

### #3. ddl-auto=update에서 Flyway 전환 시 컬럼 누락
**증상**: BaseEntity 추가 후 `updated_at` 컬럼이 없어서 앱 기동 실패
**원인**: Flyway V1이 새 스키마를 만들지만 기존 DB에는 `updated_at`이 없음
**해결**: `baseline-on-migrate=true` + V1에 `IF NOT EXISTS` + 기존 DB는 수동으로 ALTER TABLE

### #4. 예약 확정 직후 Kafka 메시지가 안 보임 (Outbox)

**증상**: DB 에 예약은 생겼는데 `ticketing.seat-hold-events` 에 `RESERVATION_CONFIRMED` 가 바로 안 보인다.

**원인**: 해당 이벤트는 **`ReservationConfirmedEventListener` 가 보내지 않는다.** `kafka_outbox` 에 쌓인 뒤 **`KafkaOutboxPublishScheduler`** 가 주기(`ticketing.outbox.publish-interval-ms`)로 읽어 전송한다.

**확인**: `kafka_outbox` 테이블에 `PENDING` 행이 있는지, 스케줄러 로그·`ticketing_outbox_published_total` 메트릭, 브로커 지연 시 `FAILED` 행 여부.

### (추후 부하 테스트 중 발견되는 이슈 추가)

---

> 새 이슈는 **증상 → 원인 → 해결 → 재발 방지** 형식으로 추가한다.
> 형식: 증상 → 원인 → 해결 → (있으면) 재발 방지
