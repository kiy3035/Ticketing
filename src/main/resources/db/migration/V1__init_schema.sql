-- ============================================================
-- V1: 초기 스키마 (콘서트 예매 시스템)
-- 실행 환경: MySQL 8.x
-- ============================================================

CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    pw VARCHAR(120) NOT NULL,
    email VARCHAR(100) NOT NULL,
    phone VARCHAR(20),
    oauth_provider VARCHAR(32),
    oauth_subject VARCHAR(255),
    noti_type VARCHAR(20) NOT NULL DEFAULT 'sms',
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    point BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY uk_users_oauth (oauth_provider, oauth_subject)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS concert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    venue VARCHAR(200) NOT NULL,
    concert_at DATETIME,
    status VARCHAR(20) NOT NULL DEFAULT 'UPCOMING',
    category VARCHAR(20) NOT NULL,
    seller_id BIGINT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT fk_concert_seller FOREIGN KEY (seller_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS seat (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    concert_id BIGINT NOT NULL,
    section VARCHAR(50) NOT NULL,
    seat_no VARCHAR(20) NOT NULL,
    price BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_seat_concert_section_no (concert_id, section, seat_no),
    CONSTRAINT fk_seat_concert FOREIGN KEY (concert_id) REFERENCES concert(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS reservation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    concert_id BIGINT NOT NULL,
    seat_id BIGINT NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    reserved_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT fk_reservation_concert FOREIGN KEY (concert_id) REFERENCES concert(id),
    CONSTRAINT fk_reservation_seat FOREIGN KEY (seat_id) REFERENCES seat(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS payment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_key VARCHAR(40) NOT NULL,
    hold_token VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    concert_id BIGINT NOT NULL,
    seat_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    payment_method VARCHAR(20) NOT NULL DEFAULT 'POINT',
    order_id VARCHAR(64),
    toss_payment_key VARCHAR(64),
    status VARCHAR(20) NOT NULL DEFAULT 'READY',
    reservation_id BIGINT,
    approved_at DATETIME,
    completed_at DATETIME,
    canceled_at DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_payment_payment_key (payment_key),
    UNIQUE KEY uk_payment_hold_token (hold_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
