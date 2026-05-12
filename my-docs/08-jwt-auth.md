# 08. JWT 인증 — Access·Refresh·블랙리스트

**아이디/비밀번호 로그인 + JWT (HS256)** 만 사용. **Spring Session 사용 안 함.**

---

## 1. 토큰 종류와 저장소

| 토큰 | TTL | 어디 저장 | 무효화 방법 |
|------|-----|-----------|-------------|
| Access JWT | 30분 (`ticketing.jwt.access-ttl-minutes`) | 클라이언트 sessionStorage | Redis 블랙리스트 `jwt:bl:{jti}` (TTL=남은 만료시간) |
| Refresh JWT | 14일 (`ticketing.jwt.refresh-ttl-days`) | 클라이언트 sessionStorage + DB `refresh_tokens` | DB `revoked = true` |

서명 키: `ticketing.jwt.secret` (HS256, UTF-8 32바이트 이상). 운영에서는 `JWT_SECRET` 환경변수로 주입.

---

## 2. 로그인 → 토큰 발급

```
POST /api/auth/login { username, password }
  ↓
AuthenticationManager.authenticate(UsernamePasswordAuthenticationToken)  // BCrypt 검증
  ↓
JwtTokenIssueService.issueForUsername(username)
  ├ Access JWT 발급 (jti = UUID)
  ├ Refresh JWT 발급 (jti = UUID)
  └ RefreshTokenPersistenceService.saveNew(jti, username, expiresAt)
       └ refresh_tokens INSERT { user_id, jti, expires_at, revoked=false }
  ↓
응답: { accessToken, refreshToken }
```

---

## 3. 인증 필터 (`JwtAuthenticationFilter`)

```
요청 헤더:
  Authorization: Bearer <accessToken>
  X-Refresh-Token: <refreshToken>

JwtAuthenticationFilter.doFilterInternal()
  ↓
SecurityContextHolder.clearContext()
  ↓
JwtAuthenticationService.authenticate(request, response)
  → 4가지 케이스 분기 (Access 유효성 × Refresh 유효성 조합)
```

공개 경로 (필터 스킵): `PublicEndpointPaths.isJwtSkipped()` 가 결정 — `/api/auth/login`, `/api/auth/signup`, `/api/queue/**`, `/actuator/**`, `/api-docs/**`, `/swagger-ui/**`, 정적 리소스 등

---

## 4. 4가지 처리 케이스

| 케이스 | Access | Refresh | 동작 | 응답 헤더 |
|--------|--------|---------|------|-----------|
| 1. 정상 | 유효 | 유효 | 그대로 인증 통과 | (없음) |
| 2. Access 만료 | 만료 | 유효 | 새 Access 발급 (Refresh 그대로 유지) | `X-New-Access-Token` |
| 3. Refresh 만료 | 유효 | 만료 | Access 살아있으니 정상 통과 (자동 재발급 안 함) | (없음) |
| 4. 둘 다 만료 | 만료 | 만료 | 401 — 재로그인 필요 | (없음) |

프론트 `apiFetch` 가 응답에 `X-New-Access-Token` 가 있으면 sessionStorage 에 갱신.

**Case 3에서 Refresh 자동 재발급을 안 하는 이유**: 자동 갱신해주면 사실상 영구 세션이 되어 보안상 위험. 14일 주기 재로그인을 디폴트로 강제.

---

## 5. Access 블랙리스트 (Redis)

JWT는 stateless라 "만료 전 즉시 무효화"가 불가능 → Redis로 보완.

```java
TokenBlacklistService.blacklistAccessJti(jti, accessExpiresAt)
  → SET jwt:bl:{jti} = "1" EX (남은 만료 시간 초)
```

- 로그아웃 시 `JwtAuthenticationService.logout()` 가 호출 → Access jti 블랙리스트 등록 + Refresh jti `revoked = true`
- 인증 필터는 매 요청마다 `TokenBlacklistService.isAccessBlacklisted(jti)` 검사 — true면 401

**왜 Redis인가?** 앱 인스턴스 2대 이상에서도 **모든 인스턴스가 같은 블랙리스트 참조**해야 하므로 공유 저장소 필요. nginx 뒤 다중 인스턴스 환경에서 필수.

---

## 6. 로그아웃 (`POST /api/auth/logout`)

```
Header: Authorization: Bearer <access>, X-Refresh-Token: <refresh>
  ↓
AuthApiController.logout()
  ├ activeUserTracker.removeActive(username)  // active:users ZSet에서 제거
  └ JwtAuthenticationService.logout(authorization, refreshToken)
       ├ Access jti → TokenBlacklistService.blacklistAccessJti
       └ Refresh jti → revokeByJti  // 해당 Refresh만 revoked 처리
  ↓
204 No Content
```

---

## 7. DB 스키마

### `refresh_tokens` (V5, V7→V8 정리)

```sql
CREATE TABLE refresh_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    jti VARCHAR(36) NOT NULL,
    expires_at DATETIME NOT NULL,
    revoked TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_refresh_jti (jti),
    KEY idx_refresh_user (user_id),
    CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES users (id)
);
```

V6에서 `users` 의 `oauth_provider`, `oauth_subject` 컬럼·유니크 인덱스 제거 (소셜 로그인 미사용).
V7에서 회전·탈취 탐지용 `family_id` 추가했다가 V8에서 다시 제거 (단순화).

---

## 8. 면접 한 줄

> **JWT(Access 30분 + Refresh 14일) + Redis Access 블랙리스트 + DB Refresh `revoked` 마킹으로 로그아웃·다중 인스턴스에서의 토큰 무효화 약점을 보완한 무상태 인증 구조.**
