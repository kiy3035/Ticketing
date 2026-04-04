# Flyway 마이그레이션 가이드

## 기본 개념
- `spring.jpa.hibernate.ddl-auto=validate` → JPA가 스키마를 자동 변경하지 않음
- Flyway가 `src/main/resources/db/migration/` 폴더의 SQL 파일을 순서대로 실행
- `flyway_schema_history` 테이블에 실행 이력 기록

## 파일 명명 규칙
```
V{버전}__{설명}.sql
V1__init_schema.sql     ← 초기 스키마
V2__add_performance_indexes.sql  ← 인덱스 추가
V3__add_column_example.sql       ← 컬럼 추가 (예시)
```

**주의:**
- `V` 다음은 숫자 (소수점 가능: V1.1)
- `__` (언더스코어 2개)가 버전과 설명을 구분
- 한번 적용된 마이그레이션은 **절대 수정하지 않는다** (체크섬 검증 실패)

## 기존 DB에 적용 시
```properties
spring.flyway.baseline-on-migrate=true
spring.flyway.baseline-version=0
```
- 기존 DB에 `flyway_schema_history`가 없으면 baseline 자동 생성
- `baseline-version=0`이므로 V1부터 실행됨
- **이미 테이블이 있으면?** → `CREATE TABLE IF NOT EXISTS`로 안전하게 처리

## 새 마이그레이션 추가 시 체크리스트
1. `src/main/resources/db/migration/V{N}__description.sql` 파일 생성
2. `IF NOT EXISTS` / `IF EXISTS` 사용 (멱등성)
3. 로컬에서 테스트: `./gradlew bootRun`으로 마이그레이션 적용 확인
4. **절대** 이미 적용된 V{N} 파일을 수정하지 않기
5. 롤백이 필요하면 V{N+1}에서 역방향 DDL 작성

## 자주 하는 실수
```
❌ V1 수정 → 체크섬 불일치 → 앱 기동 실패
✅ V3 새로 만들어서 변경사항 추가

❌ ddl-auto=update와 Flyway 동시 사용
✅ ddl-auto=validate (Flyway만 스키마 관리)

❌ 마이그레이션 파일에 DML (INSERT/UPDATE) 섞기
✅ DDL과 DML 분리 (V2__schema.sql, V2.1__seed_data.sql)
```
