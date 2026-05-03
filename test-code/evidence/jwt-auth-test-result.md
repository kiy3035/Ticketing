# JWT 인증 통합 테스트 — 실행 결과

> 실행일: 2026-05-03
> 환경: Windows 11 / PowerShell (UTF-8) / Docker Desktop (Testcontainers 자동 기동)
> 명령어: `./gradlew test --tests "JwtAuthenticationIntegrationTest" --tests "IdempotencyServiceTest" --rerun-tasks`

---

## 결과 요약

![JWT 테스트 결과](../images/jwt%20테스트%20결과.png)

| 항목 | 결과 |
|------|------|
| 테스트 수 | **8개** |
| 통과 | **8개** |
| 실패 | **0개** |
| 테스트 실행 시간 | **5.579s** (Testcontainers 기동 포함 전체: 1분 8초) |
| 빌드 결과 | **BUILD SUCCESSFUL** |

> Testcontainers가 MySQL 8.0 + Redis 7 + Kafka 컨테이너를 자동으로 기동·종료함.
> 운영 서버·인프라 서버에 연결하지 않고 독립 실행.

---

## 테스트별 통과 내역

| # | 시나리오 | 결과 |
|---|----------|------|
| 1 | 로그아웃 → Access 즉시 차단 (Redis 블랙리스트 등록 + 보호 API 401) | ✅ PASSED |
| 2 | 로그아웃 후 Refresh로 재발급 시도 → 401 (DB revoked) | ✅ PASSED |
| 3 | 만료 Access + 유효 Refresh → 새 Access 헤더(X-New-Access-Token)로 자동 재발급 | ✅ PASSED |
| 4 | 사용자 A의 Access + 사용자 B의 Refresh → 401 (sub 불일치) | ✅ PASSED |
| 5 | 짧은 TTL Access 블랙리스트 등록 → TTL 경과 후 키 자동 삭제 | ✅ PASSED |
| 6 | 위조된 서명의 Access 토큰 → 401 (서명 검증 실패) | ✅ PASSED |
| 7 | Access + Refresh 둘 다 만료 (Case 1) → 401 | ✅ PASSED |
| 8 | 형식이 깨진 JWT 문자열 → 401 (파싱 불가) | ✅ PASSED |

---

## 실제 콘솔 출력

```
JwtAuthenticationIntegrationTest > Access + Refresh 둘 다 만료 (Case 1) → 401 PASSED
JwtAuthenticationIntegrationTest > 형식이 깨진 JWT 문자열 → 401 (파싱 불가) PASSED
JwtAuthenticationIntegrationTest > 사용자 A 의 Access + 사용자 B 의 Refresh → 401 (sub 불일치) PASSED
JwtAuthenticationIntegrationTest > 위조된 서명의 Access 토큰 → 401 (서명 검증 실패) PASSED
JwtAuthenticationIntegrationTest > 만료 Access + 유효 Refresh → 새 Access 헤더(X-New-Access-Token)로 자동 재발급 PASSED
JwtAuthenticationIntegrationTest > 로그아웃 → Access 즉시 차단 (Redis 블랙리스트 등록 + 보호 API 401) PASSED
JwtAuthenticationIntegrationTest > 로그아웃 후 Refresh 로 재발급 시도 → 401 (DB revoked) PASSED
JwtAuthenticationIntegrationTest > 짧은 TTL Access 블랙리스트 등록 → TTL 경과 후 키 자동 삭제 PASSED

BUILD SUCCESSFUL in 1m 8s
5 actionable tasks: 5 executed
```

---

## 검증 내용 상세

### 시나리오 1 — Redis 블랙리스트
- 로그아웃 시 Access jti를 `jwt:bl:{jti}` 키로 Redis에 등록
- 같은 Access로 `GET /api/reservations/me` 호출 → **401**
- `redisTemplate.hasKey("jwt:bl:" + jti)` → **true** 확인

### 시나리오 2 — DB revoke
- 로그아웃 시 DB `refresh_tokens.revoked = true` 마킹
- 만료된 Access + revoke된 Refresh로 Case 2 경로 진입 → DB 검증 실패 → **401**
- Refresh JWT 자체는 서명·만료 유효하나 DB에서 차단됨을 확인

### 시나리오 3 — Case 2 자동 재발급
- 만료된 Access + 유효한 Refresh로 보호 API 호출
- 응답 **200 OK** + `X-New-Access-Token` 헤더로 새 Access 발급
- 새 Access가 `parseSignedClaimsLenient()` 서명 검증 통과 확인

### 시나리오 4 — sub 불일치 방어
- A의 Access + B의 Refresh (subject 다름) → **401**
- 토큰 짜깁기(cross-user token mixing) 공격 차단 확인

### 시나리오 5 — TTL 자동 만료
- 짧은 TTL Access의 jti를 블랙리스트에 등록
- TTL 경과 후 Redis 키 자동 삭제 확인 (메모리 무한 누적 방지)

---

## 인프라 구성 (Testcontainers 자동 기동)

| 컨테이너 | 이미지 | 용도 |
|----------|--------|------|
| MySQL | `mysql:8.0` | refresh_tokens, users 테이블 (create-drop) |
| Redis | `redis:7-alpine` | jwt:bl:{jti} 블랙리스트 저장·조회 |
| Kafka | `confluentinc/cp-kafka:7.5.0` | 테스트 컨텍스트 로딩용 (이 테스트에선 직접 사용 안 함) |

---

## 면접 활용 포인트

> "JWT stateless 약점(로그아웃 즉시 무효화 불가)을 Redis 블랙리스트 + DB revoke 두 가지로 보완했고,
> 두 경로가 모두 401을 반환함을 Testcontainers 기반 통합 테스트로 검증했습니다.
> Redis는 공유 저장소라 ALB 뒤 2대 환경에서도 어느 인스턴스로 라우팅되든 동일하게 차단됩니다."
