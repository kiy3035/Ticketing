# OAuth2 로그인 (Google)

## 한 줄 요약

브라우저는 Google **인가 코드**만 주고받고, **액세스 토큰 교환·사용자 정보 조회는 서버(Spring Security OAuth2 Client)** 가 수행한다. 콜백 직후 **내부 `users` 행**과 매핑해, 예매·알림 등 기존 API가 쓰는 **`username`(문자열)** 과 `SecurityContext`의 principal 이름을 맞춘다.

## 왜 이렇게 했는가

| 항목 | 선택 |
|------|------|
| 플로 | Authorization Code (서버 사이드 콜백) |
| 세션 | 기존과 동일 — **Spring Session + Redis** |
| IdP | Google (`registrationId`: `google`) |
| 가입 | 별도 “구글 회원가입” 화면 없음 — **첫 로그인 시 JIT(자동 가입)** |
| 폼 로그인 | 유지 — ID/비밀번호 로그인과 병행 |

## 데이터베이스

- `users.oauth_provider`, `users.oauth_subject`로 IdP 계정 단위 매핑 (복합 유니크).
- OAuth 전용 계정은 `phone`이 없을 수 있음(null). 알림은 기본적으로 이메일 위주.
- 비밀번호 컬럼은 스키마상 NOT NULL이므로 **랜덤 BCrypt** 저장(폼 로그인으로는 로그인 불가).

## 설정

환경 변수(또는 `.env`):

- `GOOGLE_CLIENT_ID`
- `GOOGLE_CLIENT_SECRET`

Google Cloud 콘솔의 **승인된 리디렉션 URI**에 다음이 등록되어 있어야 한다.

- 로컬 예: `http://localhost:8080/login/oauth2/code/google`

`application.properties`의 `spring.security.oauth2.client.registration.google.*`로 주입된다.

## 사용자 입장에서의 경로

1. `/login.html`에서 “Google로 로그인” → `/oauth2/authorization/google`
2. Google 동의 후 → `/login/oauth2/code/google` (콜백)
3. 성공 시 역할에 따라 `app.html` / `seller.html` / `admin.html` 등으로 리다이렉트 (폼 로그인과 동일한 성공 핸들러)

## 상세 (원리·시퀀스·코드 위치)

구현 세부, 시퀀스 다이어그램, 삭제 시 동작 등은 [my-docs/07-oauth2-login.md](../my-docs/07-oauth2-login.md)를 참고한다.
