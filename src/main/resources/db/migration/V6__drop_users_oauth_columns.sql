-- JWT 전환 후 미사용: users 의 소셜 연동 컬럼·유니크 인덱스 제거
ALTER TABLE users DROP INDEX uk_users_oauth;
ALTER TABLE users DROP COLUMN oauth_provider, DROP COLUMN oauth_subject;
