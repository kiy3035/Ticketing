# Flyway 마이그레이션 가이드

## 기본 개념
- `spring.jpa.hibernate.ddl-auto=validate` → JPA가 스키마를 자동 변경하지 않음
- Flyway가 `src/main/resources/db/migration/` 의 SQL 파일을 순서대로 실행
- `flyway_schema_history` 테이블에 실행 이력·체크섬 기록

## 파일 명명 규칙
```
V{버전}__{설명}.sql
V1__init_schema.sql
V2__add_performance_indexes.sql
```
**주의**:
- `V` 다음은 숫자 (소수점 가능: V1.1)
- `__` (언더스코어 2개)가 버전과 설명을 구분
- 한 번 적용된 마이그레이션은 **절대 수정하지 않는다** (체크섬 검증 실패)

## 기존 DB에 적용 시
```properties
spring.flyway.baseline-on-migrate=true
spring.flyway.baseline-version=0
```
- 기존 DB에 `flyway_schema_history`가 없으면 baseline 자동 생성
- `baseline-version=0`이므로 V1부터 실행됨
- 이미 테이블이 있어도 `CREATE TABLE IF NOT EXISTS` 로 안전하게 스킵

---

## 이 프로젝트의 마이그레이션 현황

| 버전 | 파일 | 내용 |
|------|------|------|
| V1 | `V1__init_schema.sql` | 초기 스키마: `users`, `concert`, `seat`, `reservation`, `payment` |
| V2 | `V2__add_performance_indexes.sql` | 성능 인덱스 6개 추가 (예약/콘서트/결제/좌석 복합 인덱스) |
| V3 | `V3__add_audit_columns.sql` | `BaseEntity` 도입으로 `created_at` / `updated_at` 컬럼 보정 (프로시저로 IF NOT EXISTS 처리) |
| V4 | `V4__kafka_outbox.sql` | Transactional Outbox 테이블 (`RESERVATION_CONFIRMED` 발행용) |
| V5 | `V5__jwt_refresh_tokens.sql` | JWT 도입 — `refresh_tokens` 테이블 (jti·revoke 관리) |
| V6 | `V6__drop_users_oauth_columns.sql` | JWT 전환 후 미사용 — `users.oauth_provider`, `oauth_subject` + 유니크 인덱스 제거 |
| V7 | `V7__refresh_token_family.sql` | Refresh family 도입 — `refresh_tokens.family_id` 컬럼 + 인덱스 추가 (탈취 탐지용) |

### V2 인덱스 목록 (자주 조회되는 컬럼 조합)
- `idx_reservation_user_status` — 사용자별 예매 내역
- `idx_concert_status_at` — 예매 가능/지난 공연 필터
- `idx_payment_status_completed` — 관리자 통계, 환불 배치
- `idx_payment_user_id` — 사용자별 결제 내역
- `idx_payment_concert_status` — 판매자 매출, 환불 배치
- `idx_seat_concert_status` — 가용 좌석 수 계산

### V4 outbox 테이블 인덱스
- `idx_kafka_outbox_status_id` — `WHERE status = 'PENDING' ORDER BY id` 조회 최적화

---

## 새 마이그레이션 추가 체크리스트

1. `src/main/resources/db/migration/V{N}__description.sql` 파일 생성
2. `IF NOT EXISTS` / `IF EXISTS` 사용 (멱등성)
3. 로컬에서 테스트: `./gradlew bootRun` 으로 마이그레이션 적용 확인
4. **절대** 이미 적용된 V{N} 파일을 수정하지 않기
5. 롤백이 필요하면 V{N+1}에서 역방향 DDL 작성 (예: V6가 OAuth 컬럼 drop)
6. JPA Entity와 컬럼 일치 확인 (`ddl-auto=validate` 가 catch)

## 자주 하는 실수
```
❌ V1 수정 → 체크섬 불일치 → 앱 기동 실패
✅ V8 새로 만들어서 변경사항 추가

❌ ddl-auto=update와 Flyway 동시 사용
✅ ddl-auto=validate (Flyway만 스키마 관리)

❌ 마이그레이션 파일에 운영 데이터 INSERT 섞기
✅ DDL과 DML 분리 (V8__schema.sql, V8.1__seed_data.sql)
```

## 운영 트러블슈팅

### 체크섬 불일치 오류
```
Validate failed: Migration checksum mismatch for migration version X
```
→ V{X} 파일을 수정한 흔적. 절대 수정하지 말고, 변경사항은 새 V{N+1} 파일로.

### baseline 후 첫 실행 실패
- 기존 DB에 컬럼이 일부 없을 수 있음 → V3 처럼 프로시저 + `IF NOT EXISTS` 패턴으로 보정 마이그레이션 추가

### 테스트 DB
`application-test.properties` 에서는 Flyway 끄고 JPA `create-drop` 사용. Testcontainers MySQL 컨테이너에 매번 새 스키마 생성.
