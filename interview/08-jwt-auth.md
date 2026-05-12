# JWT 인증

---

### 🟢 Q1. 왜 세션 대신 JWT 를 선택했나요?

**A.** 다중 인스턴스 환경에서 세션을 공유하려면 Spring Session + Redis 같은 세션 저장소가 필요한데, JWT 는 stateless 라 토큰 자체에 인증 정보가 들어가 **인스턴스 간 세션 공유가 불필요**합니다. nginx 뒤 2대 스케일아웃 환경에서 스티키 세션 없이도 동작합니다.

> **🟢 Q1-1. 그럼 JWT 만의 단점은?**
> **A.** 발급 후 즉시 무효화가 어렵습니다 ("로그아웃했는데 토큰이 만료까지 살아있음"). 이 프로젝트는 **Redis Access 블랙리스트** + **DB Refresh `revoked` 마킹** 으로 보완합니다.

---

### 🟢 Q2. Access / Refresh 토큰을 어떻게 쓰나요?

**A.**
| 토큰 | TTL | 저장 위치 |
|------|-----|-----------|
| Access JWT | 30분 | sessionStorage + Authorization 헤더 |
| Refresh JWT | 14일 | sessionStorage + DB `refresh_tokens` 테이블 + X-Refresh-Token 헤더 |

요청마다 `Authorization: Bearer <access>` + `X-Refresh-Token: <refresh>` 헤더로 보내고, `JwtAuthenticationFilter` → `JwtAuthenticationService` 가 4가지 케이스 분기 처리.

---

### 🟡 Q3. Access 만료·Refresh 유효 시 어떻게?

**A.** `JwtAuthenticationService` 가:
1. Refresh JWT 서명 검증
2. DB `refresh_tokens` 에서 해당 jti 가 `revoked = false` 이고 만료 전인지 확인
3. 새 Access 발급 (Refresh 는 그대로 유지)
4. 응답 헤더 `X-New-Access-Token` 으로 내려보냄

프론트 (`api.js` `apiFetch`) 가 응답 헤더를 보고 sessionStorage 갱신.

> **🟡 Q3-1. Refresh 도 회전(rotation)시키지 않는 이유는?**
> **A.** 회전 + 탈취 탐지(family rotation)까지 가는 게 OAuth2 권고 스펙이지만, 일반 B2C 서비스에서는 단순 Refresh 저장 + 만료 시 재로그인이 더 흔합니다. 회전을 도입하면 탈취 탐지가 가능해지는 대신 정상 사용자도 일시 강제 로그아웃되는 트레이드오프가 있어, 이 프로젝트 규모에는 단순 구조(Refresh jti DB 저장 + 로그아웃 시 `revoked` 마킹)를 택했습니다. 실제로 V7에서 `family_id` 컬럼을 도입했다가 V8에서 다시 제거한 이력이 있습니다.

---

### 🟡 Q4. Refresh 만료·Access 유효 시?

**A.** Access 가 살아있으니 정상 통과. Refresh 자동 재발급은 하지 않습니다. Access 가 만료되는 시점(다음 30분)에 자연스럽게 Case 1(둘 다 만료)로 진입해 재로그인을 유도합니다.

> **🟡 Q4-1. 왜 Refresh 를 자동 갱신해주지 않나요?**
> **A.** Refresh 를 자동으로 갱신해주면 사실상 영구 세션이 되어 보안상 위험합니다. 14일 주기로 재로그인을 강제하는 게 디폴트로 안전합니다.

> **🟡 Q4-2. Access·Refresh 둘 다 만료면?**
> **A.** 401 → 재로그인 필요.

---

### 🟡 Q5. nginx 뒤 다중 인스턴스에서 Redis 블랙리스트가 필요한 이유?

**A.** Access JWT 는 stateless 라 **로그아웃 즉시 무효화**가 자체적으로 불가능합니다. `TokenBlacklistService` 가 `SET jwt:bl:{jti} = "1" EX (남은 만료 시간 초)` 로 Redis 에 등록 → 모든 인스턴스가 매 요청 `isAccessBlacklisted(jti)` 검사. 공유 저장소(Redis) 가 아니면 인스턴스마다 블랙리스트가 갈리는 문제 발생.

> **🟡 Q5-1. 블랙리스트 메모리 관리는?**
> **A.** TTL = "토큰 남은 만료 시간" 이라 Access TTL(30분) 이내에 자동 만료. 키가 무한정 쌓이지 않습니다.

---

### 🔴 Q6. Refresh 를 DB 에 두는 이유는? Redis 가 아닌 이유?

**A.** Refresh 는 **장기 토큰(14일)** + **사용자별 영속 추적**이 필요해서 DB 가 적합합니다.
- `refresh_tokens` 테이블에 `user_id`, `jti`, `expires_at`, `revoked` 컬럼
- 로그아웃 시 해당 jti 만 `revoked = true` 로 마킹
- Redis 에 두면 14일 TTL 의 대량 키 누적 + 운영 추적 어려움

Access 는 짧은 TTL + 단순 존재 여부 확인이라 Redis 가 적합 — **데이터 특성에 맞춘 분리**.

---

### 🔴 Q7. JWT 보안 측면에서 신경 쓴 부분은?

**A.**
1. **HS256 + 32바이트 이상 서명 키**: `JWT_SECRET` 환경변수 주입, 운영 키 코드/설정에 노출 금지
2. **Access TTL 30분**: 탈취 시 영향 시간 최소화
3. **로그아웃 시 Access Redis 블랙리스트 + Refresh DB revoke**: JWT 무상태 약점 보완
4. **HTTPS 전제**: 토큰을 평문 전송하지 않음 (인프라 레이어)
5. **sessionStorage 저장**: localStorage 보다 짧은 생명주기. CSRF 는 SameSite + 헤더 기반 인증으로 완화 (REST API 라 폼 기반 CSRF 위협이 적음)

> **🔴 Q7-1. 왜 sessionStorage 인가요? Cookie HttpOnly 가 더 안전하지 않나요?**
> **A.** Cookie HttpOnly 는 XSS 방어에는 강하지만 CORS/SameSite 설정 복잡도가 올라가고 CSRF 토큰 별도 관리 필요. sessionStorage 는 XSS 만 방어하면 되고 (CSP, 입력 sanitization 으로 보강), REST API + JWT 조합과 잘 맞습니다. 트레이드오프를 인지하고 선택했습니다.

---

### 🔴 Q8. 면접 한 줄 요약

> **JWT(Access 30분 + Refresh 14일) + Redis Access 블랙리스트 + DB Refresh `revoked` 마킹으로 로그아웃·다중 인스턴스에서의 토큰 무효화 약점을 보완한 무상태 인증 구조.**
