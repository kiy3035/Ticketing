-- 예약 확정 시 Kafka 발행을 DB 트랜잭션과 묶는 transactional outbox (RESERVATION_CONFIRMED 전용).
-- 애플리케이션은 행 insert 후 별도 스케줄러가 Kafka send → 성공 시 행 삭제(또는 실패 시 재시도/FAILED).
CREATE TABLE kafka_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    partition_key VARCHAR(255) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    publish_attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1024) NULL,
    INDEX idx_kafka_outbox_status_id (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
