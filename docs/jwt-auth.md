# JWT 인증

## 토큰 구조

| 항목 | Access Token | Refresh Token |
|------|-------------|---------------|
| 알고리즘 | HS256 | HS256 |
| TTL | 30분 | 14일 |
| 전달 방식 | `Authorization: Bearer <token>` | `X-Refresh-Token: <token>` |
| 저장 | 클라이언트 | 클라이언트 + DB `refresh_tokens` |
| 폐기 | Redis 블랙리스트 (`jwt:bl:{jti}`) | DB revoke (`revoked=true`) |

## 4가지 처리 케이스

| 케이스 | Access | Refresh | 동작 |
|--------|--------|---------|------|
| 1 | 만료 | 만료 | 401 → 재로그인 |
| 2 | 만료 | 유효 | Refresh 검증 후 Access 재발급 + Refresh 회전 → `X-New-Access-Token`, `X-New-Refresh-Token` |
| 3 | 유효 | 만료 | 새 Refresh 발급·DB 교체 → `X-New-Refresh-Token` |
| 4 | 유효 | 유효 | 블랙리스트 + DB 유효성 확인 후 정상 처리 |

## 토큰 탈취 감지 (family 기반)

로그인마다 `family_id`(UUID) 발급. Refresh 회전 시 동일 family로 이어짐.  
**이미 revoked된 Refresh jti로 요청이 오면** → 같은 `family_id` 전체 무효화 + 401.  
(회전된 구 토큰 재사용 = 탈취 의심)

## SSE 예외

브라우저 `EventSource`는 커스텀 헤더 미지원 →  
`/api/notifications/stream`에 한해 쿼리 파라미터 `?accessToken=...&refreshToken=...` 허용.
