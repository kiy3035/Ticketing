-- Refresh 회전·가족 단위 탈취 감지 기능 제거: family_id 컬럼/인덱스 삭제
DROP INDEX idx_refresh_family ON refresh_tokens;
ALTER TABLE refresh_tokens DROP COLUMN family_id;
