# 08. JWT 인증 — Access·Refresh·블랙리스트

**아이디/비밀번호 로그인 + JWT** 만 사용한다.

## 흐름

1. `POST /api/auth/login` 으로 `accessToken`, `refreshToken` 을 받는다 (응답은 공통 `ApiResponse` 로 감싸질 수 있음 → `data.accessToken` 등).
2. 이후 API는 `Authorization: Bearer ...` 와 `X-Refresh-Token: ...` 을 **함께** 보낸다.
3. 필터가 [docs/jwt-auth.md](../docs/jwt-auth.md) 에 정의된 4가지 경우에 따라 토큰을 재발급하고, 필요 시 응답 헤더 `X-New-Access-Token` / `X-New-Refresh-Token` 을 붙인다.
4. 프론트는 [api.js](../src/main/resources/static/js/api.js) 의 `apiFetch` / `fetchJson` 이 위 헤더를 저장·갱신한다.
5. `POST /api/auth/logout` 시 Access jti 는 Redis 블랙리스트, Refresh jti 는 DB 에서 revoke.

## DB

- `refresh_tokens`: Flyway [V5__jwt_refresh_tokens.sql](../src/main/resources/db/migration/V5__jwt_refresh_tokens.sql)

## 브라우저 저장

- `sessionStorage`: `ticketing_accessToken`, `ticketing_refreshToken` ([api.js](../src/main/resources/static/js/api.js)).

## 면접 한 줄

“세션 대신 JWT + Redis 블랙리스트 + DB refresh jti 로 로그아웃과 다중 인스턴스를 맞췄다.”
