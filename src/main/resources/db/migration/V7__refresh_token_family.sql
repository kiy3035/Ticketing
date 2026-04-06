-- Refresh 회전·가족 단위 폐기: 로그인 세션 단위 family_id
ALTER TABLE refresh_tokens ADD COLUMN family_id VARCHAR(36) NULL;
UPDATE refresh_tokens SET family_id = UUID() WHERE family_id IS NULL;
ALTER TABLE refresh_tokens MODIFY COLUMN family_id VARCHAR(36) NOT NULL;
CREATE INDEX idx_refresh_family ON refresh_tokens (family_id);
