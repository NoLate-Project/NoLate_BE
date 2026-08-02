-- Append-only native departure-alarm scheduling receipts (MySQL 8.x).
-- Stop all application instances before running. MySQL DDL commits implicitly.

DROP PROCEDURE IF EXISTS assert_departure_alarm_receipt_preconditions;
DELIMITER //
CREATE PROCEDURE assert_departure_alarm_receipt_preconditions()
BEGIN
    IF (
        SELECT COUNT(*) FROM application_schema_migrations
        WHERE version = '2026-08-01-departure-alarm-fire-evidence-v1'
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'alarm receipt migration blocked: predecessor marker is absent';
    END IF;
    IF EXISTS (
        SELECT 1 FROM application_schema_migrations
        WHERE version = '2026-08-01-departure-alarm-schedule-receipts-v1'
    ) OR EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_schedule_receipts'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'alarm receipt migration blocked: partial or applied schema requires inspection';
    END IF;
END//
DELIMITER ;

CALL assert_departure_alarm_receipt_preconditions();
DROP PROCEDURE assert_departure_alarm_receipt_preconditions;

CREATE TABLE departure_alarm_schedule_receipts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    client_receipt_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    device_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    command_receipt_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    alarm_id VARCHAR(100) NOT NULL,
    schedule_id BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    desired_generation_at_receipt BIGINT NOT NULL,
    desired_operation_at_receipt VARCHAR(16) NOT NULL,
    generation_relation VARCHAR(16) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    trigger_at DATETIME(6) NULL,
    outcome VARCHAR(16) NOT NULL,
    applied BOOLEAN NOT NULL,
    scheduled BOOLEAN NOT NULL,
    platform VARCHAR(20) NOT NULL,
    delivery_mode VARCHAR(24) NOT NULL,
    source VARCHAR(16) NOT NULL,
    failure_reason VARCHAR(64) NULL,
    client_occurred_at DATETIME(6) NOT NULL,
    server_recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_departure_alarm_receipt_member_client (member_id, client_receipt_id),
    UNIQUE KEY uk_departure_alarm_receipt_member_device_command
        (member_id, device_fingerprint, command_receipt_key),
    INDEX idx_departure_alarm_receipt_cohort
        (outcome, trigger_at, platform, delivery_mode, server_recorded_at),
    INDEX idx_departure_alarm_receipt_schedule (schedule_id, server_recorded_at),
    INDEX idx_departure_alarm_receipt_member (member_id, id),
    CONSTRAINT chk_departure_alarm_receipt_generation
        CHECK (generation BETWEEN 0 AND 9007199254740991),
    CONSTRAINT chk_departure_alarm_receipt_desired_generation
        CHECK (desired_generation_at_receipt BETWEEN generation AND 9007199254740991),
    CONSTRAINT chk_departure_alarm_receipt_relation CHECK (
        (generation_relation = 'CURRENT' AND generation = desired_generation_at_receipt) OR
        (generation_relation = 'STALE' AND generation < desired_generation_at_receipt)
    ),
    CONSTRAINT chk_departure_alarm_receipt_enums CHECK (
        desired_operation_at_receipt IN ('UPSERT', 'CANCEL') AND
        operation IN ('UPSERT', 'CANCEL') AND
        outcome IN ('SCHEDULED', 'CANCELED', 'FAILED') AND
        platform IN ('ANDROID', 'IOS') AND
        delivery_mode IN (
            'ANDROID_EXACT', 'ANDROID_INEXACT', 'IOS_ALARM_KIT',
            'IOS_TIME_SENSITIVE', 'UNKNOWN'
        ) AND
        source IN ('PUSH', 'SNAPSHOT')
    ),
    CONSTRAINT chk_departure_alarm_receipt_shape CHECK (
        (outcome = 'SCHEDULED' AND operation = 'UPSERT' AND trigger_at IS NOT NULL
            AND scheduled = TRUE) OR
        (outcome = 'CANCELED' AND operation = 'CANCEL' AND trigger_at IS NULL
            AND applied = TRUE AND scheduled = FALSE AND failure_reason IS NULL) OR
        (outcome = 'FAILED' AND scheduled = FALSE AND failure_reason IS NOT NULL
            AND NOT (operation = 'CANCEL' AND applied = TRUE))
    )
) COMMENT='Authenticated append-only native alarm scheduling denominator';

DROP PROCEDURE IF EXISTS assert_departure_alarm_receipt_postconditions;
DELIMITER //
CREATE PROCEDURE assert_departure_alarm_receipt_postconditions()
BEGIN
    IF (
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_schedule_receipts'
    ) <> 22 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'alarm receipt migration verification failed';
    END IF;
    IF (
        SELECT COUNT(*) FROM (
            SELECT index_name
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'departure_alarm_schedule_receipts'
            GROUP BY index_name
            HAVING
                (index_name = 'PRIMARY' AND MIN(non_unique) = 0
                    AND GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'id')
                OR (index_name = 'uk_departure_alarm_receipt_member_client'
                    AND MIN(non_unique) = 0
                    AND GROUP_CONCAT(column_name ORDER BY seq_in_index) =
                        'member_id,client_receipt_id')
                OR (index_name = 'idx_departure_alarm_receipt_cohort'
                    AND MIN(non_unique) = 1
                    AND GROUP_CONCAT(column_name ORDER BY seq_in_index) =
                        'outcome,trigger_at,platform,delivery_mode,server_recorded_at')
                OR (index_name = 'uk_departure_alarm_receipt_member_device_command'
                    AND MIN(non_unique) = 0
                    AND GROUP_CONCAT(column_name ORDER BY seq_in_index) =
                        'member_id,device_fingerprint,command_receipt_key')
                OR (index_name = 'idx_departure_alarm_receipt_schedule'
                    AND MIN(non_unique) = 1
                    AND GROUP_CONCAT(column_name ORDER BY seq_in_index) =
                        'schedule_id,server_recorded_at')
                OR (index_name = 'idx_departure_alarm_receipt_member'
                    AND MIN(non_unique) = 1
                    AND GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'member_id,id')
        ) verified_indexes
    ) <> 6 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'alarm receipt index verification failed';
    END IF;
END//
DELIMITER ;

CALL assert_departure_alarm_receipt_postconditions();
DROP PROCEDURE assert_departure_alarm_receipt_postconditions;

INSERT INTO application_schema_migrations(version, description, applied_at)
VALUES (
    '2026-08-01-departure-alarm-schedule-receipts-v1',
    'Append-only native departure-alarm scheduling denominator',
    CURRENT_TIMESTAMP(6)
);

SELECT COUNT(*) AS departure_alarm_schedule_receipt_marker_count
FROM application_schema_migrations
WHERE version = '2026-08-01-departure-alarm-schedule-receipts-v1';
