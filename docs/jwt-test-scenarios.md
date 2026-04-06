# JWT 테스트 시나리오

JWT 인증/재발급/블랙리스트/탈취 대응의 대표 경우의수를 점검하기 위한 체크리스트다.
보호 API 예시는 `GET /api/concerts/counts`를 사용한다.

## 공통 준비

- 테스트 계정 1개 이상 준비 (`username/password`)
- 로그인: `POST /api/auth/login` 으로 `accessToken`, `refreshToken` 획득
- 인증 헤더
  - `Authorization: Bearer {accessToken}`
  - `X-Refresh-Token: {refreshToken}`
- 응답 헤더 확인 포인트
  - `X-New-Access-Token`
  - `X-New-Refresh-Token`

## 핵심 시나리오

| ID | 시나리오 | 입력 상태 | 기대 결과 |
|---|---|---|---|
| S1 | 정상 동작 | Access 유효, Refresh 유효, Access jti 미블랙리스트, Refresh DB 유효 | 200, 보호 API 성공 |
| S2 | Access만 만료 | Access 만료, Refresh 유효(미폐기/미만료) | 200, `X-New-Access-Token` + `X-New-Refresh-Token` 발급(회전), 이후 신토큰으로 재요청 성공 |
| S3 | Refresh만 만료 | Access 유효, Refresh 만료 | 200, `X-New-Refresh-Token` 발급, DB에서 구 Refresh revoke + 동일 family로 신 Refresh 저장 |
| S4 | 둘 다 만료 | Access 만료, Refresh 만료 | 401, 재로그인 필요 |
| S5 | 블랙리스트 TTL 만료 확인 | 로그아웃으로 Access jti 블랙리스트 등록 후 TTL 경과 | TTL 이전: 401, TTL 이후: (다른 조건 유효 시) 블랙리스트 미적중 |
| S6 | 토큰 탈취(회전 후 구 Refresh 재사용) | 회전으로 이미 revoked 된 구 Refresh 재제시 | 401, 해당 `family_id` 전체 revoke(정상 클라이언트도 재로그인 필요) |

## 추가 권장 시나리오

| ID | 시나리오 | 입력 상태 | 기대 결과 |
|---|---|---|---|
| E1 | 로그아웃 이후 차단 | 로그아웃 직후 동일 Access/Refresh 재사용 | Access: 401(블랙리스트), Refresh: family revoke 상태로 401 |
| E2 | 서명 위조/비밀키 불일치 | JWT payload는 정상처럼 보이나 서명 무효 | 401 |
| E3 | 토큰 타입 위조 | `typ=refresh`를 Access 자리로 전달 또는 반대 | 401 |
| E4 | subject 불일치 | Access subject != Refresh subject | 401 |
| E5 | 헤더 누락 | `Authorization` 또는 `X-Refresh-Token` 누락 | 401 |
| E6 | DB 미존재 jti | JWT는 유효하나 DB에 해당 Refresh jti 없음 | 401 |
| E7 | 이미 revoke 된 Refresh 재사용(일반) | 구 Refresh 반복 제출 | 401 + 가족 전체 폐기(탈취 의심 처리) |
| E8 | 만료 직전 경계값 | exp 직전/직후 타이밍 요청 | 직전: 통과 가능, 직후: 만료 처리(케이스별 재발급/401) |

## 실행 가이드 (수동/자동 공통)

1. `S1`을 먼저 통과시켜 기본 인증 체인 정상 여부 확인
2. `S2`와 `S3`로 재발급 헤더 및 DB 상태(`refresh_tokens`) 검증
3. `S4`, `E2~E6`로 실패 분기 401 일관성 검증
4. `S6`, `E7`로 탈취 대응 핵심 로직(가족 전체 폐기) 검증
5. `S5`는 Redis TTL 특성상 대기 시간이 필요하므로 별도 잡으로 분리

## 관찰 포인트

- 서버 로그: 401 사유(만료/서명오류/재사용 탐지) 구분 가능하게 남는지
- DB: `refresh_tokens.revoked`, `family_id` 별 폐기 범위
- Redis: `jwt:bl:{jti}` 키 생성/TTL 감소/자동 삭제
- 클라이언트: `X-New-*` 수신 시 토큰 저장 교체가 즉시 반영되는지
