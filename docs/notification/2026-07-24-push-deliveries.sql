-- Per-device at-most-once boundary for logical push events.
--
-- Important: DISPATCHING is committed before the provider call. A row that remains
-- DISPATCHING/UNKNOWN after a process exit MUST NOT be automatically retried because
-- FCM does not expose a server-side idempotency key that can prove the first call was
-- not accepted. Operators must inspect push_send_history/provider evidence and either
-- leave it suppressed or create an explicit compensating event with a new event key.
CREATE TABLE IF NOT EXISTS push_deliveries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version BIGINT NOT NULL DEFAULT 0,
    member_id BIGINT NOT NULL,
    event_key VARCHAR(100) NOT NULL,
    device_key VARCHAR(100) NOT NULL,
    device_token_id BIGINT NULL,
    device_id VARCHAR(100) NULL,
    platform VARCHAR(20) NOT NULL,
    schedule_id BIGINT NULL,
    payload_type VARCHAR(80) NULL,
    status VARCHAR(30) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 1,
    first_attempted_at DATETIME(6) NOT NULL,
    last_attempted_at DATETIME(6) NOT NULL,
    delivered_at DATETIME(6) NULL,
    provider_message_id VARCHAR(300) NULL,
    error_code VARCHAR(120) NULL,
    error_message VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_push_deliveries_member_event_device (member_id, event_key, device_key),
    INDEX idx_push_deliveries_member_event (member_id, event_key),
    INDEX idx_push_deliveries_status_attempted_at (status, last_attempted_at),
    INDEX idx_push_deliveries_schedule_id (schedule_id)
) COMMENT='Durable at-most-once per-device push delivery boundary';
