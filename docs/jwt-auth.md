# JWT 인증

## 1. 토큰 구조

| 항목 | Access Token | Refresh Token |
|------|-------------|---------------|
| 알고리즘 | HS256 | HS256 |
| TTL | 30분 | 14일 |
| 전달 방식 | `Authorization: Bearer <token>` | `X-Refresh-Token: <token>` |
| 저장 위치 | 클라이언트 메모리 | 클라이언트 + DB `refresh_tokens` |
| 폐기 방식 | Redis 블랙리스트 (`jwt:bl:{jti}`) | DB `revoked = true` |
| jti 용도 | 블랙리스트 키 | DB 1:1 매핑, family 추적 |

**알고리즘 선택 이유**: HS256은 대칭키(단일 시크릿)다. 이 프로젝트처럼 **단일 백엔드가 발급·검증 모두 담당**하는 구조에서는 RS256(비대칭)의 이점이 없고, 키 관리가 단순한 HS256이 적합하다.

---

## 2. 스케일아웃 환경에서의 무상태 인증

현재 앱 서버가 2대(nginx 로드밸런서)로 운영되며, JWT는 이 환경에서도 문제없이 동작한다.

```
클라이언트
    │
    ▼
 nginx (로드밸런서)
    │
    ├─ 앱 서버 1 ─── JWT 서명 검증 (동일 secret)
    └─ 앱 서버 2 ─── JWT 서명 검증 (동일 secret)
                              │
                              ├── Redis (블랙리스트 공유)
                              └── MySQL (refresh_tokens 공유)
```

- Access 검증은 **서명 확인만으로 완결** → 서버 간 세션 공유 불필요
- 로그아웃·탈취 감지처럼 상태가 필요한 부분만 **Redis(블랙리스트)와 MySQL(refresh_tokens)을 공유**
- 새 서버를 추가해도 secret과 DB 연결만 맞추면 즉시 동작

---

## 3. 4가지 처리 케이스

매 요청마다 Access·Refresh 두 토큰을 함께 전송하고, 만료 조합에 따라 4가지로 분기한다.

| 케이스 | Access | Refresh | 동작 | 응답 헤더 |
|--------|--------|---------|------|-----------|
| 1 | 만료 | 만료 | 401 → 재로그인 | - |
| 2 | 만료 | 유효 | Refresh DB 검증 → Access 재발급 + Refresh 회전 | `X-New-Access-Token`, `X-New-Refresh-Token` |
| 3 | 유효 | 만료 | Access 블랙리스트 확인 → 새 Refresh 발급·DB 교체 | `X-New-Refresh-Token` |
| 4 | 유효 | 유효 | 블랙리스트 + DB 유효성 확인 후 정상 처리 | - |

### 왜 매 요청에 Refresh도 같이 보내나?

"Access만 만료됐을 때" 별도 `/api/auth/refresh` 엔드포인트를 두면 클라이언트가 401을 받고 → refresh 요청 → 원래 요청 재시도하는 3-step이 된다. 매 요청에 두 토큰을 함께 보내면 **서버가 투명하게 재발급**해서 클라이언트 로직이 단순해진다.

---

## 4. Refresh 토큰 회전 (Rotation)

Refresh를 한 번 사용하면 새 Refresh를 발급하고 구 Refresh는 즉시 폐기한다.

```
로그인     → Refresh-A 발급, DB 저장 (familyId=F1)
Case 2     → Refresh-A 폐기(revoked), Refresh-B 발급 (familyId=F1 유지)
Case 2     → Refresh-B 폐기(revoked), Refresh-C 발급 (familyId=F1 유지)
```

**효과**: 탈취된 구 Refresh로 새 Access를 발급받을 수 없다.

---

## 5. 탈취 감지 (Family 기반)

```
정상 사용자: Refresh-B로 요청 → Refresh-C 발급
공격자:      회전 전에 훔친 Refresh-A로 요청
                  → DB에 Refresh-A가 revoked 상태로 존재
                  → "이미 폐기된 토큰 재사용 = 탈취 의심"
                  → familyId=F1 전체(Refresh-C 포함) 즉시 무효화
                  → 정상 사용자도 강제 재로그인
```

**구현**: `RefreshTokenPersistenceService.detectReuseOfRevokedRefreshAndInvalidateFamily()` — 모든 요청에서 **만료 여부 확인 전에** 먼저 실행한다.

---

## 6. 로그아웃

```
1. Refresh jti → familyId 전체 revoke (DB)
2. Access jti  → 남은 유효 시간만큼 Redis 블랙리스트 등록
```

**왜 Access를 Redis에 넣나?**: JWT는 서명만 유효하면 만료 전까지 사용 가능하다. 로그아웃 후에도 탈취된 Access로 요청이 들어올 수 있어, jti를 Redis에 기록해 만료 전까지 차단한다. TTL은 토큰 잔여 유효 시간과 일치시켜 **자동 삭제**로 Redis 메모리를 낭비하지 않는다.

---

## 7. SSE 예외 처리

브라우저 `EventSource`는 커스텀 헤더(`Authorization`, `X-Refresh-Token`)를 지원하지 않아, SSE 경로에 한해 **쿼리 파라미터**로 토큰을 받는다.

```
/api/notifications/stream?accessToken=...&refreshToken=...
```

**보안 조치**: 쿼리 파라미터에 토큰이 노출되면 nginx 로그에 평문으로 기록된다. 이를 방지하기 위해 nginx에서 해당 파라미터를 마스킹한다.

```nginx
# nginx.conf
map $request_uri $loggable_uri {
    ~^/api/notifications/stream  "/api/notifications/stream?[TOKEN_MASKED]";
    default                       $request_uri;
}
log_format main '... "$request_method $loggable_uri ..."';
```

---

## 8. 비밀키 관리

```java
// JwtTokenService.initKey()
if (secret.getBytes(UTF_8).length < 32) {
    throw new IllegalStateException("...");
}
```

- 기동 시 32바이트 미만이면 즉시 서버 시작 실패 → 짧은 키로 운영되는 사고 방지
- 운영 환경: `JWT_SECRET` 환경변수로 주입 (소스코드에 하드코딩 금지)
- 개발 기본값은 `application.properties`에 별도 명시

---

## 9. 설계 결정 요약

| 결정 | 이유 |
|------|------|
| Access + Refresh 매 요청 동반 | 클라이언트 재시도 로직 제거, 투명한 재발급 |
| jti(UUID) 사용 | 로그아웃·블랙리스트·탈취 추적을 토큰 전체 저장 없이 구현 |
| Redis 블랙리스트 (Access) | Access TTL만큼만 보관 후 자동 삭제, 메모리 효율 |
| DB refresh_tokens (Refresh) | 탈취 감지·family 추적에 영속 저장 필요 |
| family 기반 탈취 감지 | 구 토큰 재사용 즉시 탐지 + family 전체 무효화로 피해 최소화 |
| Case 3 단일 트랜잭션 (replaceRefresh) | 서버 2대 환경에서 revoke + saveNew 사이 race condition 방지 |
| nginx 로그 마스킹 | SSE 쿼리 파라미터 토큰 유출 차단 |
