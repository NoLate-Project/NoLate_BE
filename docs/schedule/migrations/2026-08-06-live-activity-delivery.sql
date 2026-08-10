-- ActivityKit push-to-start/update/end state and immutable provider outbox (MySQL 8.x).
-- Stop API and worker instances before applying this reviewed additive migration.

DROP PROCEDURE IF EXISTS assert_live_activity_preconditions;
DELIMITER //
CREATE PROCEDURE assert_live_activity_preconditions()
BEGIN
    IF (
        SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name IN ('application_schema_migrations', 'member', 'schedules', 'push_device_token')
    ) <> 4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'live activity migration blocked: required ownership tables are absent';
    END IF;
    IF EXISTS (
        SELECT 1 FROM application_schema_migrations
        WHERE version = '2026-08-06-live-activity-delivery-v1'
          AND description <> 'ActivityKit state, credentials, and immutable provider outbox'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'live activity migration blocked: marker is incompatible';
    END IF;
END//
DELIMITER ;

CALL assert_live_activity_preconditions();
DROP PROCEDURE assert_live_activity_preconditions;

CREATE TABLE IF NOT EXISTS live_activity_lifecycle_intents (
    id BIGINT NOT NULL AUTO_INCREMENT,
    scope_type VARCHAR(30) NOT NULL,
    member_id BIGINT NULL,
    schedule_id BIGINT NULL,
    reason VARCHAR(80) NOT NULL,
    completed_at DATETIME(6) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL DEFAULT '1970-01-01 00:00:00.000000',
    last_attempted_at DATETIME(6) NULL,
    last_error_code VARCHAR(120) NULL,
    dead_lettered_at DATETIME(6) NULL,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_live_activity_lifecycle_intent_pending
        (completed_at, dead_lettered_at, available_at, id),
    CONSTRAINT chk_live_activity_lifecycle_intent_scope CHECK (
        (scope_type = 'MEMBER_SCHEDULE' AND member_id IS NOT NULL AND schedule_id IS NOT NULL) OR
        (scope_type = 'SCHEDULE' AND member_id IS NULL AND schedule_id IS NOT NULL) OR
        (scope_type = 'MEMBER' AND member_id IS NOT NULL AND schedule_id IS NULL)
    )
) COMMENT='Durable source-transaction lifecycle intents for Live Activity terminal projection';


CREATE TABLE IF NOT EXISTS live_activity_start_tokens (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    push_device_token_id BIGINT NOT NULL,
    device_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    activity_type VARCHAR(120) NOT NULL,
    push_to_start_token VARCHAR(500) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'Sensitive ActivityKit credential; never log or expose',
    token_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    token_generation BIGINT NOT NULL DEFAULT 1,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at DATETIME(6) NOT NULL,
    dispatch_lease_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    dispatch_lease_until DATETIME(6) NULL,
    retirement_requested BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_live_activity_start_token_device_type
        (member_id, device_fingerprint, activity_type),
    UNIQUE KEY uk_live_activity_start_token_fingerprint (token_fingerprint),
    INDEX idx_live_activity_start_token_device (push_device_token_id, active, id),
    INDEX idx_live_activity_start_token_lease (dispatch_lease_until, id),
    CONSTRAINT chk_live_activity_start_token_generation CHECK (token_generation >= 1)
) COMMENT='Per-device ActivityKit push-to-start credentials and ownership leases';

CREATE TABLE IF NOT EXISTS schedule_live_activities (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version BIGINT NOT NULL DEFAULT 0,
    member_id BIGINT NOT NULL,
    schedule_id BIGINT NOT NULL,
    push_device_token_id BIGINT NOT NULL,
    bound_fcm_ownership_version BIGINT NOT NULL DEFAULT 0,
    device_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    activity_type VARCHAR(120) NOT NULL,
    start_token_id BIGINT NULL,
    start_token_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    start_token_generation BIGINT NULL,
    activity_id VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NULL,
    update_token VARCHAR(500) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'Sensitive per-activity ActivityKit credential; never log or expose',
    update_token_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    update_token_generation BIGINT NOT NULL DEFAULT 0,
    state VARCHAR(30) NOT NULL,
    source_notification_generation BIGINT NOT NULL,
    source_check_count INT NOT NULL,
    content_revision BIGINT NOT NULL,
    content_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attributes_json TEXT NOT NULL,
    content_state_json TEXT NOT NULL,
    last_apns_timestamp BIGINT NOT NULL DEFAULT 0,
    stale_at DATETIME(6) NULL,
    expires_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NULL,
    end_reason VARCHAR(80) NULL,
    late_end_ack_session_generation BIGINT NULL,
    late_end_ack_push_device_token_id BIGINT NULL,
    late_end_ack_fcm_ownership_version BIGINT NULL
        COMMENT 'Frozen retired-session and FCM ownership epoch allowed to ACK this exact END',
    dispatch_lease_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    dispatch_lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_live_activity_schedule_member_device
        (schedule_id, member_id, device_fingerprint),
    UNIQUE KEY uk_schedule_live_activity_update_token_fingerprint (update_token_fingerprint),
    INDEX idx_schedule_live_activity_member_state (member_id, state, id),
    INDEX idx_schedule_live_activity_expiry (state, expires_at, id),
    INDEX idx_schedule_live_activity_terminal_retention (state, ended_at, id),
    INDEX idx_schedule_live_activity_lease (dispatch_lease_until, id),
    CONSTRAINT chk_schedule_live_activity_state CHECK (
        state IN (
            'START_PENDING', 'START_SENT', 'ACTIVE', 'TOKEN_PENDING',
            'END_PENDING', 'ENDED', 'INVALID'
        )
    ),
    CONSTRAINT chk_schedule_live_activity_revision CHECK (
        source_notification_generation >= 0 AND source_check_count >= 0 AND content_revision >= 1
        AND update_token_generation >= 0 AND last_apns_timestamp >= 0
    )
) COMMENT='Per-schedule/device Live Activity desired state and monotonic projection fence';

CREATE TABLE IF NOT EXISTS live_activity_deliveries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version BIGINT NOT NULL DEFAULT 0,
    event_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    live_activity_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    schedule_id BIGINT NOT NULL,
    push_device_token_id BIGINT NOT NULL,
    fcm_token_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fcm_ownership_version BIGINT NOT NULL,
    device_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    activity_token VARCHAR(500) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'Frozen sensitive ActivityKit route; never log or expose',
    activity_token_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    start_token_id BIGINT NULL,
    start_token_generation BIGINT NULL,
    update_token_generation BIGINT NULL,
    target_activity_id VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NULL,
    operation VARCHAR(20) NOT NULL,
    revision BIGINT NOT NULL,
    source_notification_generation BIGINT NOT NULL,
    apns_timestamp BIGINT NOT NULL,
    activity_type VARCHAR(120) NOT NULL,
    attributes_json TEXT NULL,
    content_state_json TEXT NOT NULL,
    alert_title VARCHAR(100) NULL,
    alert_body VARCHAR(300) NULL,
    stale_at DATETIME(6) NULL,
    dismissal_at DATETIME(6) NULL,
    expiration_at DATETIME(6) NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL,
    first_attempted_at DATETIME(6) NULL,
    last_attempted_at DATETIME(6) NULL,
    provider_message_id VARCHAR(300) NULL,
    error_code VARCHAR(120) NULL,
    error_message VARCHAR(500) NULL,
    dispatch_lease_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    dispatch_lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_live_activity_delivery_event_key (event_key),
    INDEX idx_live_activity_delivery_due (status, available_at, id),
    INDEX idx_live_activity_delivery_activity (live_activity_id, revision, id),
    INDEX idx_live_activity_delivery_member (member_id, schedule_id, id),
    CONSTRAINT chk_live_activity_delivery_operation CHECK (operation IN ('START', 'UPDATE', 'END')),
    CONSTRAINT chk_live_activity_delivery_status CHECK (
        status IN ('PENDING', 'DISPATCHING', 'SUCCESS', 'FAILED', 'UNCERTAIN',
                   'INVALID', 'EXHAUSTED', 'SUPERSEDED')
    ),
    CONSTRAINT chk_live_activity_delivery_counters CHECK (
        revision >= 1 AND source_notification_generation >= 0
        AND apns_timestamp >= 1 AND attempt_count >= 0
        AND (update_token_generation IS NULL OR update_token_generation >= 1)
    )
) COMMENT='Immutable per-device Live Activity provider outbox with at-most-once ambiguity boundary';

DROP PROCEDURE IF EXISTS assert_live_activity_postconditions;
DELIMITER //
CREATE PROCEDURE assert_live_activity_postconditions()
BEGIN
    IF (
        SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name IN (
              'live_activity_lifecycle_intents', 'live_activity_start_tokens',
              'schedule_live_activities', 'live_activity_deliveries'
          )
    ) <> 4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'live activity migration verification failed: tables are absent';
    END IF;
    IF (
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND (
              (table_name = 'live_activity_lifecycle_intents' AND column_name IN (
                  'scope_type', 'completed_at', 'attempt_count', 'available_at',
                  'dead_lettered_at'
              )) OR
              (table_name = 'live_activity_start_tokens' AND column_name = 'token_generation') OR
              (table_name = 'schedule_live_activities' AND column_name IN (
                  'source_notification_generation', 'content_revision', 'update_token_generation',
                  'bound_fcm_ownership_version', 'late_end_ack_session_generation',
                  'late_end_ack_push_device_token_id', 'late_end_ack_fcm_ownership_version'
              )) OR
              (table_name = 'live_activity_deliveries' AND column_name IN (
                  'source_notification_generation', 'revision', 'update_token_generation',
                  'dispatch_lease_id'
              ))
          )
    ) <> 17 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'live activity migration verification failed: monotonic fence columns are absent';
    END IF;
    IF (
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'live_activity_lifecycle_intents'
    ) <> 17 OR (
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'live_activity_start_tokens'
    ) <> 19 OR (
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'schedule_live_activities'
    ) <> 38 OR (
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'live_activity_deliveries'
    ) <> 44 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'live activity migration verification failed: exact column contract differs';
    END IF;
    IF (
        SELECT COUNT(DISTINCT CONCAT(table_name, ':', index_name))
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND index_name IN (
              'idx_live_activity_lifecycle_intent_pending',
              'uk_live_activity_start_token_device_type',
              'uk_live_activity_start_token_fingerprint',
              'idx_live_activity_start_token_device',
              'idx_live_activity_start_token_lease',
              'uk_schedule_live_activity_schedule_member_device',
              'uk_schedule_live_activity_update_token_fingerprint',
              'idx_schedule_live_activity_member_state',
              'idx_schedule_live_activity_expiry',
              'idx_schedule_live_activity_terminal_retention',
              'idx_schedule_live_activity_lease',
              'uk_live_activity_delivery_event_key',
              'idx_live_activity_delivery_due',
              'idx_live_activity_delivery_activity',
              'idx_live_activity_delivery_member'
          )
    ) <> 15 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'live activity migration verification failed: ownership indexes differ';
    END IF;
END//
DELIMITER ;

CALL assert_live_activity_postconditions();
DROP PROCEDURE assert_live_activity_postconditions;

INSERT INTO application_schema_migrations(version, description, applied_at)
SELECT
    '2026-08-06-live-activity-delivery-v1',
    'ActivityKit state, credentials, and immutable provider outbox',
    CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (
    SELECT 1 FROM application_schema_migrations
    WHERE version = '2026-08-06-live-activity-delivery-v1'
);

SELECT COUNT(*) AS live_activity_migration_marker_count
FROM application_schema_migrations
WHERE version = '2026-08-06-live-activity-delivery-v1';
