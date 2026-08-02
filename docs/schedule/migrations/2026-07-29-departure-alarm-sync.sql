-- Durable native departure-alarm desired state and hidden control outbox (MySQL 8.x).
--
-- Stop all application instances before running. MySQL DDL commits implicitly, so a partial
-- application must be inspected and completed manually; this script deliberately refuses both
-- an existing table/column and an existing marker. Start the new binary only after every
-- postcondition succeeds and the marker count is exactly one.

DROP PROCEDURE IF EXISTS assert_departure_alarm_sync_preconditions;
DELIMITER //
CREATE PROCEDURE assert_departure_alarm_sync_preconditions()
BEGIN
    IF (
        SELECT COUNT(*)
        FROM application_schema_migrations
        WHERE version = '2026-07-29-departure-alarm-mode-v1'
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'departure alarm sync migration blocked: alarm-mode predecessor marker is absent';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_sync_state'
    ) OR EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'app_notifications'
          AND column_name = 'inbox_visible'
    ) OR EXISTS (
        SELECT 1
        FROM application_schema_migrations
        WHERE version = '2026-07-29-departure-alarm-sync-v1'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'departure alarm sync migration blocked: partial or already-applied schema requires inspection';
    END IF;
END//
DELIMITER ;

CALL assert_departure_alarm_sync_preconditions();
DROP PROCEDURE assert_departure_alarm_sync_preconditions;

ALTER TABLE app_notifications
    ADD COLUMN inbox_visible BOOLEAN NOT NULL DEFAULT TRUE
        COMMENT 'Whether this durable row appears in the user inbox and unread count'
        AFTER created_at;

CREATE TABLE departure_alarm_sync_state (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Departure alarm desired-state primary key',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    member_id BIGINT NOT NULL COMMENT 'Alarm recipient member id',
    schedule_id BIGINT NOT NULL COMMENT 'Schedule id; deliberately no FK so tombstones survive deletion',
    alarm_id VARCHAR(100) NOT NULL COMMENT 'Stable client alarm identifier',
    generation BIGINT NOT NULL DEFAULT 0 COMMENT 'Monotonic command generation per alarm id',
    operation VARCHAR(16) NOT NULL COMMENT 'UPSERT or CANCEL',
    trigger_at DATETIME(6) NULL COMMENT 'Native alarm trigger time for UPSERT',
    title VARCHAR(100) NULL COMMENT 'Native alarm title for UPSERT',
    snooze_minutes INT NULL COMMENT 'Native alarm default snooze minutes for UPSERT',
    command_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'Canonical latest-command SHA-256',
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_departure_alarm_sync_member_schedule (member_id, schedule_id),
    UNIQUE KEY uk_departure_alarm_sync_alarm_id (alarm_id),
    INDEX idx_departure_alarm_sync_member_id (member_id, id),
    INDEX idx_departure_alarm_sync_expiry (operation, trigger_at, id),
    CONSTRAINT chk_departure_alarm_sync_generation
        CHECK (generation BETWEEN 0 AND 9007199254740991),
    CONSTRAINT chk_departure_alarm_sync_operation
        CHECK (operation IN ('UPSERT', 'CANCEL')),
    CONSTRAINT chk_departure_alarm_sync_shape
        CHECK (
            (
                operation = 'UPSERT' AND
                trigger_at IS NOT NULL AND
                title IS NOT NULL AND
                snooze_minutes BETWEEN 1 AND 60
            ) OR (
                operation = 'CANCEL' AND
                trigger_at IS NULL AND
                title IS NULL AND
                snooze_minutes IS NULL
            )
        )
) COMMENT='Latest native departure alarm desired state and CANCEL tombstones';

DROP PROCEDURE IF EXISTS assert_departure_alarm_sync_postconditions;
DELIMITER //
CREATE PROCEDURE assert_departure_alarm_sync_postconditions()
BEGIN
    IF (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'app_notifications'
          AND column_name = 'inbox_visible'
          AND data_type = 'tinyint'
          AND is_nullable = 'NO'
          AND column_default IN ('1', 'b''1''')
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'departure alarm sync migration verification failed: inbox visibility column is invalid';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_sync_state'
          AND column_name IN (
              'id', 'version', 'member_id', 'schedule_id', 'alarm_id', 'generation',
              'operation', 'trigger_at', 'title', 'snooze_minutes', 'command_fingerprint',
              'create_dt', 'update_dt'
          )
    ) <> 13 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'departure alarm sync migration verification failed: state column contract is incomplete';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM (
            SELECT index_name
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'departure_alarm_sync_state'
              AND index_name IN (
                  'uk_departure_alarm_sync_member_schedule',
                  'uk_departure_alarm_sync_alarm_id',
                  'idx_departure_alarm_sync_member_id',
                  'idx_departure_alarm_sync_expiry'
              )
            GROUP BY index_name
            HAVING
                (
                    index_name = 'uk_departure_alarm_sync_member_schedule' AND
                    MIN(non_unique) = 0 AND
                    GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'member_id,schedule_id'
                ) OR (
                    index_name = 'uk_departure_alarm_sync_alarm_id' AND
                    MIN(non_unique) = 0 AND
                    GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'alarm_id'
                ) OR (
                    index_name = 'idx_departure_alarm_sync_member_id' AND
                    MIN(non_unique) = 1 AND
                    GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'member_id,id'
                ) OR (
                    index_name = 'idx_departure_alarm_sync_expiry' AND
                    MIN(non_unique) = 1 AND
                    GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'operation,trigger_at,id'
                )
        ) verified_alarm_indexes
    ) <> 4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'departure alarm sync migration verification failed: state indexes are incomplete';
    END IF;
END//
DELIMITER ;

CALL assert_departure_alarm_sync_postconditions();
DROP PROCEDURE assert_departure_alarm_sync_postconditions;

INSERT INTO application_schema_migrations(version, description, applied_at)
VALUES (
    '2026-07-29-departure-alarm-sync-v1',
    'Durable native departure-alarm desired state and hidden control outbox',
    CURRENT_TIMESTAMP(6)
);

SELECT COUNT(*) AS departure_alarm_sync_marker_count
FROM application_schema_migrations
WHERE version = '2026-07-29-departure-alarm-sync-v1';

SELECT table_name, column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND (
      table_name = 'departure_alarm_sync_state' OR
      (table_name = 'app_notifications' AND column_name = 'inbox_visible')
  )
ORDER BY table_name, ordinal_position;
