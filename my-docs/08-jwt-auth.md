# 08. JWT 인증 — Access·Refresh·Family·블랙리스트

**아이디/비밀번호 로그인 + JWT (HS256)** 만 사용. **Spring Session 사용 안 함.**

---

## 1. 토큰 종류와 저장소

| 토큰 | TTL | 어디 저장 | 무효화 방법 |
|------|-----|-----------|-------------|
| Access JWT | 30분 (`ticketing.jwt.access-ttl-minutes`) | 클라이언트 sessionStorage | Redis 블랙리스트 `jwt:bl:{jti}` (TTL=남은 만료시간) |
| Refresh JWT | 14일 (`ticketing.jwt.refresh-ttl-days`) | 클라이언트 sessionStorage + DB `refresh_tokens` | DB `revoked = true` 또는 family 단위 일괄 무효화 |

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
  └ RefreshTokenPersistenceService.saveNew(jti, username, expiresAt, familyId=새 UUID)
       └ refresh_tokens INSERT { user_id, jti, family_id, expires_at, revoked=false }
  ↓
응답: { accessToken, refreshToken }
```

**familyId**: 로그인 한 번당 한 UUID. 회전(rotation) 시 같은 family에 새 jti 행을 쌓는다.

---

## 3. 인증 필터 (`JwtAuthenticationFilter`)

```
요청 헤더:
  Authorization: Bearer <accessToken>
  X-Refresh-Token: <refreshToken>   ← 갱신 흐름에서 함께 보내짐

JwtAuthenticationFilter.doFilterInternal()
  ↓
SecurityContextHolder.clearContext()
  ↓
JwtAuthenticationService.authenticate(request, response)
  → 4가지 케이스 분기 (Access 유효성 × Refresh 유효성 조합)
```

공개 경로 (필터 스킵): `PublicEndpointPaths.isJwtSkipped()` 가 결정 — `/api/auth/login`, `/api/auth/signup`, `/api/queue/**`, `/actuator/**`, `/api-docs/**`, `/swagger-ui/**`, 정적 리소스 등

---

## 4. 4가지 갱신 케이스

| 케이스 | Access | Refresh | 동작 | 응답 헤더 |
|--------|--------|---------|------|-----------|
| 1. 정상 | 유효 | 유효 | 그대로 인증 통과 | (없음) |
| 2. Access 만료 | 만료 | 유효 | 새 Access + 새 Refresh 발급, 구 Refresh jti revoke (rotation) | `X-New-Access-Token`, `X-New-Refresh-Token` |
| 3. Refresh 만료 | 유효 | 만료 | 새 Refresh 발급 (Access 그대로 유지) | `X-New-Refresh-Token` |
| 4. 둘 다 만료 | 만료 | 만료 | 401 — 재로그인 필요 | (없음) |

프론트 `apiFetch` 가 응답에 `X-New-Access-Token` / `X-New-Refresh-Token` 가 있으면 sessionStorage에 갱신.

---

## 5. Refresh 회전 + 가족(family) 기반 탈취 탐지

### 회전 (rotation)
케이스 2에서 Access 재발급 시 Refresh도 함께 새 jti로 발급:
```java
RefreshTokenPersistenceService.rotateRefreshAfterAccessRenewal(oldRefreshJti, username, newRefreshJti, newExpiresAt)
  ├ 구 jti.revoked = true
  └ saveNew(newJti, ..., 같은 familyId)  // 가족은 유지
```

### 탈취 탐지
**이미 revoked된 jti로 재요청이 오면 = 회전 후 구 토큰을 누군가 재사용 = 탈취 의심**:
```java
RefreshTokenPersistenceService.detectReuseOfRevokedRefreshAndInvalidateFamily(refreshJti)
  ├ DB 조회 → revoked == true 면
  └ refreshTokenRepository.revokeAllByFamilyId(rt.getFamilyId())  // family 전체 무효화
```

→ 같은 로그인 세션의 모든 Refresh가 한꺼번에 죽음 → 공격자도 합법 사용자도 모두 재로그인 필요 (안전한 디폴트)

---

## 6. Access 블랙리스트 (Redis)

JWT는 stateless라 "만료 전 즉시 무효화"가 불가능 → Redis로 보완.

```java
TokenBlacklistService.blacklistAccessJti(jti, accessExpiresAt)
  → SET jwt:bl:{jti} = "1" EX (남은 만료 시간 초)
```

- 로그아웃 시 `JwtAuthenticationService.logout()` 가 호출 → Access jti 블랙리스트 등록 + Refresh family 전체 무효화
- 인증 필터는 매 요청마다 `TokenBlacklistService.isAccessBlacklisted(jti)` 검사 — true면 401

**왜 Redis인가?** 앱 인스턴스 2대 이상에서도 **모든 인스턴스가 같은 블랙리스트 참조**해야 하므로 공유 저장소 필요. ALB 뒤 다중 인스턴스 환경에서 필수.

---

## 7. 로그아웃 (`POST /api/auth/logout`)

```
Header: Authorization: Bearer <access>, X-Refresh-Token: <refresh>
  ↓
AuthApiController.logout()
  ├ activeUserTracker.removeActive(username)  // active:users ZSet에서 제거
  └ JwtAuthenticationService.logout(authorization, refreshToken)
       ├ Access jti → TokenBlacklistService.blacklistAccessJti
       └ Refresh jti → revokeEntireFamilyByRefreshJti  // family 전체 revoke
  ↓
204 No Content
```

---

## 8. DB 스키마

### `refresh_tokens` (V5 + V7)

```sql
CREATE TABLE refresh_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    jti VARCHAR(36) NOT NULL,
    family_id VARCHAR(36) NOT NULL,           -- V7에서 추가
    expires_at DATETIME NOT NULL,
    revoked TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_refresh_jti (jti),
    KEY idx_refresh_user (user_id),
    KEY idx_refresh_family (family_id),       -- V7에서 추가
    CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES users (id)
);
```

V6에서 `users` 의 `oauth_provider`, `oauth_subject` 컬럼·유니크 인덱스 제거 (소셜 로그인 미사용).

---

## 9. 면접 한 줄

> **세션 대신 JWT + Redis Access 블랙리스트 + DB Refresh family 회전·탈취 탐지로 로그아웃·다중 인스턴스·토큰 재사용 공격을 모두 커버한다.**
