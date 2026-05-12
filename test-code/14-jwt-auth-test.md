# 14. JWT 인증 통합 테스트 (시나리오 설계)

> JWT 의 **무상태(stateless) 약점 — 로그아웃 즉시 무효화 불가** 를 어떻게 보완했는지 코드로 증명하는 산출물.
> 면접에서 "JWT 쓰면 로그아웃해도 토큰이 살아있는 거 아닌가요?" "다중 인스턴스에서 어떻게 같은 토큰을 차단하나요?" 라는 질문에 **테스트 코드 + 통과 리포트** 로 답할 수 있게 한다.

---

## 1. 배경 — 왜 이 테스트가 포폴로 가치가 있나

### JWT 의 약점
JWT 는 서명만 유효하면 만료 전까지 어떤 서버에서든 통과한다. 즉 **로그아웃 직후에도 탈취된 Access 로 요청이 통과**할 수 있다. 이 프로젝트는 두 가지 보완책으로 막는다.

| 약점 | 보완책 | 위치 |
|------|--------|------|
| 로그아웃된 Access 가 만료 전까지 살아있음 | **Redis 블랙리스트** `jwt:bl:{jti}` (TTL = 남은 만료시간) | `TokenBlacklistService` |
| 로그아웃된 Refresh 로 새 Access 재발급 가능 | **DB `refresh_tokens.revoked = true`** 마킹 후 검증 | `RefreshTokenPersistenceService` |

이 두 보완책이 실제로 동작하는지 — 그리고 **다중 인스턴스(공유 저장소)** 환경을 의식하고 설계됐는지 — 가 면접 어필 포인트.

### Case 2 자동 재발급
4-case 분기 중 가장 사용자 영향이 큰 흐름이다. Access 만료 시점에 **클라이언트가 별도 `/refresh` 호출 없이** 응답 헤더로 새 Access 를 받아 다음 요청에 쓴다. 이걸 통합 테스트로 명시적으로 보여주면 "JWT 4-case 처리" 스토리에 무게가 실린다.

---

## 2. 핵심 구현 (검증 대상)

```java
// JwtAuthenticationService.authenticate(req, res) — 4-case 분기
// Case 1: 둘 다 만료      → 401
// Case 2: Access 만 만료  → DB Refresh 검증 후 X-New-Access-Token 헤더로 새 Access 발급
// Case 3: Refresh 만 만료 → Access 살아있으면 통과 (자동 재발급 안 함)
// Case 4: 둘 다 유효      → 블랙리스트 + DB Refresh 검증 후 통과

// JwtAuthenticationService.logout(authHeader, refreshHeader)
//   ├ Refresh jti → refreshTokenPersistenceService.revokeByJti(jti)
//   └ Access jti  → tokenBlacklistService.blacklistAccessJti(jti, exp)
```

### 핵심 설계 포인트

| 항목 | 설명 |
|------|------|
| 매 요청 두 토큰 동반 | 별도 `/refresh` 엔드포인트 없이 인증 필터에서 투명하게 재발급 → 클라이언트 재시도 로직 단순화 |
| Redis 블랙리스트 TTL = Access 잔여 시간 | 메모리 자동 회수, 키 무한 누적 방지 |
| Refresh 는 회전(rotation) 안 함 | 정상 사용자 강제 로그아웃 트레이드오프 회피. 대신 jti 단위 폐기로 로그아웃 시점 제어 (V7→V8 에서 family 설계 제거 이력) |
| sub 일치 검사 | Access 와 Refresh 의 subject 가 다르면 즉시 401 — 토큰 짜깁기 방어 |

---

## 3. 5가지 시나리오

`JwtAuthenticationIntegrationTest extends IntegrationTestBase` (Testcontainers MySQL + Redis 사용)

| # | 시나리오 | 검증 내용 | 왜 중요한가 |
|---|----------|-----------|-------------|
| 1 | **로그아웃 후 Access 즉시 차단** | 로그아웃 → 같은 Access 로 보호된 API 호출 → 401, Redis 에 `jwt:bl:{jti}` 키 존재 | JWT stateless 약점 보완. 다중 인스턴스 공유 저장소 의도 검증 |
| 2 | **로그아웃 후 Refresh 재발급 차단** | 로그아웃 → 만료된 Access + 살아있는 Refresh 로 요청 → 401 (DB `revoked = true`) | Refresh 회전 없이도 로그아웃 시점에 재발급 경로가 막히는지 |
| 3 | **Case 2 자동 재발급** | 만료된 Access + 유효 Refresh 로 요청 → 200 + 응답 헤더 `X-New-Access-Token` 존재, 새 Access 가 유효한 JWT | 4-case 분기 중 가장 핵심. 클라이언트 재시도 없는 투명한 재발급 |
| 4 | **다른 사용자의 Refresh 도용 차단** | 사용자 A 의 Access + 사용자 B 의 Refresh 로 요청 → 401 (sub 불일치) | jti 도용·토큰 짜깁기 공격 방어 |
| 5 | **블랙리스트 TTL 자동 만료** | 짧은 TTL(2초) Access 를 블랙리스트 등록 → 3초 대기 → Redis 키 사라짐 | 메모리 무한 누적 방지. 운영 안정성 어필 |

> 시나리오 5 는 운영 관점 어필이라 단위 테스트(`TokenBlacklistServiceTest`) 로 분리해도 됨. 통합 5개로 묶을지 4 통합 + 1 단위 로 나눌지는 작성 시 선택.

---

## 4. 테스트 코드 위치 & 뼈대

### 통합 테스트
```
src/test/java/com/inyoung/ticketing/auth/jwt/
└── JwtAuthenticationIntegrationTest.java
```

### 뼈대 패턴 (시나리오 1 예시)
```java
@Test
@DisplayName("로그아웃 → 같은 Access 로 보호된 API 호출 → 401, Redis 블랙리스트 키 존재")
void logout_blacklistsAccessJti_andRejectsSubsequentRequests() {
    // given: 로그인 토큰 쌍 발급
    Users user = createUser("alice");
    TokenPairResponse pair = jwtTokenIssueService.issueForUsername(user.getUsername());
    String accessJti = jwtTokenService.parseSignedClaimsLenient(pair.getAccessToken()).get().getId();

    // when: 로그아웃
    jwtAuthenticationService.logout("Bearer " + pair.getAccessToken(), pair.getRefreshToken());

    // then: Redis 블랙리스트 키 + 보호 API 호출 401
    assertThat(redisTemplate.hasKey("jwt:bl:" + accessJti)).isTrue();
    assertThat(callProtectedApi(pair.getAccessToken(), pair.getRefreshToken()).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
}
```

### 헬퍼
| 메서드 | 역할 |
|--------|------|
| `createUser(String username)` | DB 에 테스트 유저 insert (`UsersRepository`) |
| `issuePair(String username)` | 정상 토큰 쌍 발급 (로그인 시뮬레이션) |
| `issueExpiredAccess(...)` / `issueExpiredRefresh(...)` | TTL 을 음수로 만들거나 `application-test.properties` 의 `access-ttl-seconds=1` 활용해 만료 토큰 생성 |
| `callProtectedApi(access, refresh)` | `TestRestTemplate` 으로 보호된 엔드포인트 호출 (예: `GET /api/reservations/me`) |

### 만료 토큰 만드는 법
- 테스트 전용 빈 또는 `JwtTokenService` 의 `createAccessToken` 직접 호출 + reflection 으로 exp 변경 → 비추천
- **권장**: `application-test.properties` 에 `ticketing.jwt.access-ttl-seconds=1`, `ticketing.jwt.refresh-ttl-seconds=1` 같은 짧은 TTL 옵션을 두고, 시나리오별로 `@TestPropertySource` 로 덮어쓰거나 `Awaitility` 로 잠깐 대기 후 사용

---

## 5. 실행 방법 + 기대 출력

```bash
./gradlew test --tests JwtAuthenticationIntegrationTest

# 기대 출력
JwtAuthenticationIntegrationTest > 로그아웃 → ... 401, Redis 블랙리스트 키 존재 PASSED
JwtAuthenticationIntegrationTest > 로그아웃 → 만료된 Access + 살아있는 Refresh 로 요청 → 401 PASSED
JwtAuthenticationIntegrationTest > 만료된 Access + 유효 Refresh → X-New-Access-Token 헤더로 새 Access 발급 PASSED
JwtAuthenticationIntegrationTest > 사용자 A 의 Access + 사용자 B 의 Refresh → 401 (sub 불일치) PASSED
JwtAuthenticationIntegrationTest > 짧은 TTL Access 블랙리스트 등록 → TTL 경과 후 키 자동 삭제 PASSED

BUILD SUCCESSFUL
```

---

## 6. 면접 어필 포인트

1. **무상태 인증의 약점을 인지하고 보완책을 코드로 증명** — "JWT 쓰면 로그아웃 즉시 무효화가 안 되니 Redis 블랙리스트 + DB revoke 로 보완했고, 두 보완책이 모두 동작함을 통합 테스트로 검증했다"
2. **다중 인스턴스 의식** — Redis(공유 저장소) 검증을 Testcontainers Redis 로 실제로 돌려본다. "nginx 뒤 2대 환경" 답변과 코드가 일치
3. **회전을 의도적으로 안 한 트레이드오프 명시** — V7 에서 family 도입 후 V8 에서 제거한 이력 → "스펙 이해는 하지만 프로젝트 규모에 맞춰 단순화했다" 라는 의사결정 어필
4. **자동 재발급의 클라이언트 영향** — Case 2 시나리오로 "별도 /refresh 호출 없이 헤더로 끝낸 이유" 답변과 코드 일치

---

## 7. 면접 답변 스크립트

### Q1. JWT 쓰면 로그아웃해도 토큰이 살아있는 거 아닌가요?

> "맞습니다, JWT 자체는 stateless 라 만료 전까지는 유효합니다. 그래서 두 가지 보완책을 적용했습니다.
> Access 는 로그아웃 시 jti 를 Redis `jwt:bl:{jti}` 에 토큰 잔여 시간만큼 TTL 로 등록하고, 매 요청 인증 필터가 이 키를 검사합니다.
> Refresh 는 DB `refresh_tokens.revoked = true` 로 마킹해서, 만료된 Access 가 재발급 받으려 할 때 차단합니다.
> 통합 테스트로 두 경로 모두 401 이 떨어지는 걸 검증했습니다."

### Q2. 다중 인스턴스 환경에서는 어떻게 같은 블랙리스트를 공유하나요?

> "Redis 가 공유 저장소라 인스턴스마다 따로 갈리지 않습니다. 만약 인스턴스 로컬 메모리에 두면 서버 1에서 로그아웃한 토큰이 서버 2로 가면 통과되어 버리겠죠.
> Testcontainers 로 실제 Redis 를 띄워서 검증했고, 운영에서는 인프라 서버의 Redis 를 모든 앱 인스턴스가 같이 바라봅니다."

### Q3. Refresh 도 회전(rotation) 시키지 않는 이유는?

> "OAuth2 권고 스펙은 회전 + family 단위 탈취 탐지입니다. 실제로 V7 에서 `family_id` 컬럼을 도입했다가 V8 에서 다시 제거했습니다.
> 회전을 도입하면 탈취 탐지는 가능해지는 대신, 동시 요청·뒤로가기 같은 정상 케이스에서도 정상 사용자가 강제 로그아웃되는 트레이드오프가 큽니다.
> B2C 티켓팅 서비스 규모에서는 단순한 jti 저장 + 로그아웃 시 폐기로도 보안 요구가 충분하다고 판단했습니다."

### Q4. Access 만료 시 클라이언트가 별도 `/refresh` 를 호출하지 않는 이유는?

> "매 요청에 Access 와 Refresh 를 함께 보내고, 인증 필터가 만료 조합을 보고 투명하게 재발급합니다. Access 만 만료된 경우(Case 2) 응답 헤더에 `X-New-Access-Token` 을 실어주고, 프론트는 그 헤더만 보면 됩니다.
> 별도 엔드포인트 방식은 401 → /refresh → 원요청 재시도의 3-step 이 되는데, 헤더 방식은 1-step 으로 끝납니다.
> Case 2 통합 테스트에서 응답 헤더에 새 Access 가 실제로 실리고, 그게 다음 요청에서 유효한 JWT 인지까지 검증했습니다."

---

## 8. 완료 체크리스트

- [x] `application-test.properties` 짧은 TTL 불필요 — `mintExpiredAccessToken()` 헬퍼로 직접 과거 exp 설정
- [x] 보호된 API: `GET /api/reservations/me` 픽 완료
- [x] `createUser` 헬퍼 작성 완료
- [x] 시나리오 5 통합 테스트로 유지 결정 (`Thread.sleep(2500)` 사용)
- [x] `evidence/jwt-auth-test-result.md` + `images/jwt 테스트 결과.png` 캡처 완료
- [x] `05-test-catalog.md` 와 `README.md` 에 14번 항목 추가 완료
- [x] 시나리오 6~8 추가 (서명 위조·둘 다 만료·형식 깨진 JWT) — 8개 전체 PASSED
