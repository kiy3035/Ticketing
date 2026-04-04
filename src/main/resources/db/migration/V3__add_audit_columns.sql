-- ============================================================
-- V3: BaseEntity 도입으로 인한 audit 컬럼(created_at, updated_at) 보정
-- 기존 ddl-auto=update로 생성된 테이블에 누락된 컬럼을 추가한다.
-- 이미 컬럼이 있는 테이블은 프로시저가 안전하게 스킵한다.
-- ============================================================

-- MySQL에서 "IF NOT EXISTS" 컬럼 추가를 지원하지 않으므로 프로시저 사용
DELIMITER //
CREATE PROCEDURE add_audit_columns_if_missing()
BEGIN
    -- concert.created_at
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'concert' AND column_name = 'created_at'
    ) THEN
        ALTER TABLE concert ADD COLUMN created_at DATETIME NOT NULL DEFAULT NOW();
    END IF;

    -- concert.updated_at
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'concert' AND column_name = 'updated_at'
    ) THEN
        ALTER TABLE concert ADD COLUMN updated_at DATETIME NOT NULL DEFAULT NOW();
    END IF;

    -- seat.created_at
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'seat' AND column_name = 'created_at'
    ) THEN
        ALTER TABLE seat ADD COLUMN created_at DATETIME NOT NULL DEFAULT NOW();
    END IF;

    -- seat.updated_at
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'seat' AND column_name = 'updated_at'
    ) THEN
        ALTER TABLE seat ADD COLUMN updated_at DATETIME NOT NULL DEFAULT NOW();
    END IF;

    -- reservation.created_at
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'reservation' AND column_name = 'created_at'
    ) THEN
        ALTER TABLE reservation ADD COLUMN created_at DATETIME NOT NULL DEFAULT NOW();
    END IF;

    -- reservation.updated_at
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'reservation' AND column_name = 'updated_at'
    ) THEN
        ALTER TABLE reservation ADD COLUMN updated_at DATETIME NOT NULL DEFAULT NOW();
    END IF;

    -- payment.created_at
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payment' AND column_name = 'created_at'
    ) THEN
        ALTER TABLE payment ADD COLUMN created_at DATETIME NOT NULL DEFAULT NOW();
    END IF;

    -- payment.updated_at
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'payment' AND column_name = 'updated_at'
    ) THEN
        ALTER TABLE payment ADD COLUMN updated_at DATETIME NOT NULL DEFAULT NOW();
    END IF;

    -- users.created_at
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'created_at'
    ) THEN
        ALTER TABLE users ADD COLUMN created_at DATETIME NOT NULL DEFAULT NOW();
    END IF;

    -- users.updated_at
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'updated_at'
    ) THEN
        ALTER TABLE users ADD COLUMN updated_at DATETIME NOT NULL DEFAULT NOW();
    END IF;
END //
DELIMITER ;

CALL add_audit_columns_if_missing();
DROP PROCEDURE IF EXISTS add_audit_columns_if_missing;
