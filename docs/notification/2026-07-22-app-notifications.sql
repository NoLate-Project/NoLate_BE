-- Durable in-app inbox. This is intentionally separate from push_send_history:
-- push history has one row per device attempt, while this table has one row per
-- member-facing event and remains available when a device token is missing.
CREATE TABLE IF NOT EXISTS app_notifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    deduplication_key VARCHAR(180) NULL,
    type VARCHAR(80) NOT NULL,
    schedule_id BIGINT NULL,
    category_id BIGINT NULL,
    title VARCHAR(200) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    data_json LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    read_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_notifications_member_deduplication (member_id, deduplication_key),
    INDEX idx_app_notifications_member_id_id (member_id, id),
    INDEX idx_app_notifications_member_read_at (member_id, read_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Also repairs an early local/preview mapping that could infer TINYTEXT for @Lob String.
ALTER TABLE app_notifications
    MODIFY COLUMN data_json LONGTEXT NOT NULL;
