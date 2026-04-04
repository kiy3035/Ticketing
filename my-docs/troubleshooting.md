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
**해결**: Consumer도 `JsonDeserializer` 통일 + `setRemoveTypeHeaders(true)`, `addTrustedPackages("*")`

### #3. ddl-auto=update에서 Flyway 전환 시 컬럼 누락
**증상**: BaseEntity 추가 후 `updated_at` 컬럼이 없어서 앱 기동 실패
**원인**: Flyway V1이 새 스키마를 만들지만 기존 DB에는 `updated_at`이 없음
**해결**: `baseline-on-migrate=true` + V1에 `IF NOT EXISTS` + 기존 DB는 수동으로 ALTER TABLE

### (추후 부하 테스트 중 발견되는 이슈 추가)

---

> 💡 새로운 이슈를 발견할 때마다 여기에 추가한다.
> 형식: 증상 → 원인 → 해결 → (있으면) 재발 방지
