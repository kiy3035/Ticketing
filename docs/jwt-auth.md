# JWT 인증 (Access / Refresh / 블랙리스트)

## 개요

- **Access Token**: HS256 JWT, 유효기간 **30분**, `Authorization: Bearer <access>` 로 전달.
- **Refresh Token**: HS256 JWT, 유효기간 **14일**, `X-Refresh-Token: <refresh>` 헤더로 전달.
- **Refresh 메타데이터**: DB 테이블 `refresh_tokens`(jti, `family_id`, user_id, 만료, revoked). 로그인마다 하나의 `family_id`(UUID)로 묶고, Case 2에서 Access 재발급 시 Refresh도 회전(새 jti, 동일 family)한다.
- **탈취 탐지**: DB에 이미 `revoked`인 jti로 요청이 오면(회전된 구 Refresh 재사용) 같은 `family_id`의 모든 행을 무효화하고 401을 반환한다.
- **Access 블랙리스트**: 로그아웃 시 Redis 키 `jwt:bl:{jti}` (Access 만료 시각까지 TTL).

## 요청 처리 (4가지 경우)

인증이 필요한 API에 대해 필터가 Access·Refresh를 함께 검사한다.

| 경우 | Access | Refresh | 동작 |
|------|--------|---------|------|
| 1 | 만료 | 만료 | 401, 재로그인 |
| 2 | 만료 | 유효 | Refresh 검증 후 Access 재발급 + Refresh 회전, `X-New-Access-Token`·`X-New-Refresh-Token` |
| 3 | 유효 | 만료 | Access 검증 후 Refresh 재발급, `X-New-Refresh-Token`, DB에 새 jti 저장·기존 revoke |
| 4 | 유효 | 유효 | 정상. Access jti 블랙리스트·DB refresh 행 유효성 확인 |

## 엔드포인트

- `POST /api/auth/login` — JSON `{ "username", "password" }` → `{ accessToken, refreshToken, tokenType }` (성공 응답은 `ApiResponse` 래핑).
- `POST /api/auth/logout` — `Authorization` + `X-Refresh-Token` — Access 블랙리스트(미만료만), 해당 Refresh가 속한 **family 전체** revoke.

## 설정

- `ticketing.jwt.secret` — HS256용 비밀키(UTF-8 기준 **32바이트 이상**). 운영에서는 `JWT_SECRET` 등으로 주입.
- `ticketing.jwt.access-ttl-minutes=30`
- `ticketing.jwt.refresh-ttl-days=14`

## SSE (EventSource)

브라우저 `EventSource`는 커스텀 헤더를 붙일 수 없어, `/api/notifications/stream` 에 한해 쿼리 `accessToken`, `refreshToken` 을 지원한다.

## DB 이력

- 최초 스키마(`V1__init_schema.sql`)에 있던 소셜 연동 컬럼은 **`V6__drop_users_oauth_columns.sql`** 에서 제거한다. Flyway는 과거 마이그레이션 파일을 수정하지 않는 것이 일반적이다.

## 관련 코드

- [SecurityConfig.java](../src/main/java/com/inyoung/ticketing/config/SecurityConfig.java) — `SessionCreationPolicy.STATELESS`, JWT 필터 체인 앞단.
- [JwtAuthenticationFilter.java](../src/main/java/com/inyoung/ticketing/auth/jwt/JwtAuthenticationFilter.java)
- [JwtAuthenticationService.java](../src/main/java/com/inyoung/ticketing/auth/jwt/JwtAuthenticationService.java)
- [V5__jwt_refresh_tokens.sql](../src/main/resources/db/migration/V5__jwt_refresh_tokens.sql)
- [V7__refresh_token_family.sql](../src/main/resources/db/migration/V7__refresh_token_family.sql) — `family_id` 컬럼
