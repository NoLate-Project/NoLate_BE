-- Apply only after:
--   1) 2026-07-24-push-deliveries.sql
--   2) 2026-07-24-push-delivery-followup.sql
-- and before deploying 751cbd4 + 10bd1da + this follow-up together.
--
-- Those commits have never been deployed. Any push_deliveries row or schedule-push-job
-- inbox key therefore proves that a partial/preview deployment wrote legacy event/device
-- identities. Stop instead of silently opening a new key and sending a duplicate.

DELIMITER //
CREATE PROCEDURE assert_push_linearization_preconditions()
BEGIN
    IF EXISTS (SELECT 1 FROM push_deliveries LIMIT 1) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'push linearization migration blocked: drain/inspect legacy push_deliveries first';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM app_notifications
        WHERE deduplication_key LIKE 'schedule-push-job:%'
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'push linearization migration blocked: drain prior schedule event keys first';
    END IF;
END//
DELIMITER ;

CALL assert_push_linearization_preconditions();
DROP PROCEDURE assert_push_linearization_preconditions;

-- Immutable outbox identity and canonical account binding for existing inbox rows.
ALTER TABLE app_notifications
    ADD COLUMN logical_event_key VARCHAR(100) NULL
        COMMENT 'Durable logical push/outbox event key' AFTER deduplication_key;

UPDATE app_notifications
SET logical_event_key =
    CONCAT('legacy:', SHA2(CONCAT(member_id, ':', id), 256))
WHERE logical_event_key IS NULL;

UPDATE app_notifications
SET data_json = CASE
    WHEN JSON_VALID(data_json) THEN JSON_SET(
        data_json,
        '$.logicalEventKey', logical_event_key,
        '$.recipientMemberId', CAST(member_id AS CHAR)
    )
    ELSE JSON_OBJECT(
        'logicalEventKey', logical_event_key,
        'recipientMemberId', CAST(member_id AS CHAR)
    )
END;

ALTER TABLE app_notifications
    MODIFY COLUMN logical_event_key VARCHAR(100) NOT NULL
        COMMENT 'Durable logical push/outbox event key',
    ADD UNIQUE KEY uk_app_notifications_member_logical_event
        (member_id, logical_event_key);

-- Case-sensitive one-way identity. Raw token/deviceId columns remain for provider/client
-- use but are deliberately not indexed, so duplicate-key messages contain only SHA-256.
ALTER TABLE push_device_token
    ADD COLUMN token_fingerprint CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL AFTER token,
    ADD COLUMN device_fingerprint CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL AFTER token_fingerprint,
    ADD COLUMN ownership_version BIGINT NOT NULL DEFAULT 0 AFTER device_fingerprint;

UPDATE push_device_token
SET token_fingerprint = LOWER(SHA2(CAST(token AS BINARY), 256)),
    device_fingerprint = CASE
        WHEN device_id IS NULL OR CHAR_LENGTH(TRIM(device_id)) = 0 THEN NULL
        ELSE LOWER(SHA2(CAST(TRIM(device_id) AS BINARY), 256))
    END;

-- Keep the newest row for a provider token, then the newest row for a member/device.
DELETE older
FROM push_device_token older
JOIN push_device_token newer
  ON newer.token_fingerprint = older.token_fingerprint
 AND newer.id > older.id;

DELETE older
FROM push_device_token older
JOIN push_device_token newer
  ON newer.member_id = older.member_id
 AND newer.device_fingerprint = older.device_fingerprint
 AND newer.device_fingerprint IS NOT NULL
 AND newer.id > older.id;

DELIMITER //
CREATE PROCEDURE drop_push_raw_unique_indexes()
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'push_device_token'
          AND index_name = 'uk_push_device_token_token'
    ) THEN
        ALTER TABLE push_device_token DROP INDEX uk_push_device_token_token;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'push_device_token'
          AND index_name = 'uk_push_device_token_member_device'
    ) THEN
        ALTER TABLE push_device_token DROP INDEX uk_push_device_token_member_device;
    END IF;
END//
DELIMITER ;

CALL drop_push_raw_unique_indexes();
DROP PROCEDURE drop_push_raw_unique_indexes;

ALTER TABLE push_device_token
    MODIFY COLUMN token_fingerprint CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY COLUMN device_fingerprint CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_push_device_token_token_fingerprint (token_fingerprint),
    ADD UNIQUE KEY uk_push_device_token_member_device_fingerprint
        (member_id, device_fingerprint);

-- Per-device manifest stores the exact ownership snapshot claimed before provider I/O.
-- The precondition guarantees this table is empty, so adding NOT NULL columns is safe.
ALTER TABLE push_deliveries
    CHANGE COLUMN device_id device_fingerprint CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'One-way client device fingerprint',
    ADD COLUMN token_fingerprint CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL AFTER device_token_id,
    ADD COLUMN token_ownership_version BIGINT NOT NULL AFTER token_fingerprint;

-- Meaningful-edit generation fence and confirmed-vs-uncertain schedule semantics.
ALTER TABLE schedule_push_job
    ADD COLUMN notification_input_fingerprint CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL AFTER notification_generation,
    ADD COLUMN last_handled_departure_at DATETIME(6) NULL
        AFTER last_reminder_boundary_at,
    ADD COLUMN last_handled_reminder_boundary_at DATETIME(6) NULL
        AFTER last_handled_departure_at,
    ADD COLUMN handled_departure_notice_at DATETIME(6) NULL
        AFTER departure_notice_sent_at,
    ADD COLUMN last_handled_departure_reminder_stage VARCHAR(40) NULL
        AFTER last_departure_reminder_boundary_at,
    ADD COLUMN last_handled_departure_reminder_boundary_at DATETIME(6) NULL
        AFTER last_handled_departure_reminder_stage,
    ADD COLUMN last_uncertain_at DATETIME(6) NULL
        AFTER last_handled_departure_reminder_boundary_at;

-- Existing jobs predate deterministic outbox delivery. This stable bootstrap value is
-- fenced immediately; the next actual semantic edit replaces it and opens generation 1.
UPDATE schedule_push_job
SET notification_input_fingerprint = LOWER(
    SHA2(
        CONCAT_WS(
            '|',
            member_id,
            schedule_id,
            DATE_FORMAT(schedule_at, '%Y-%m-%dT%H:%i:%s.%fZ'),
            DATE_FORMAT(departure_at, '%Y-%m-%dT%H:%i:%s.%fZ'),
            DATE_FORMAT(monitor_start_at, '%Y-%m-%dT%H:%i:%s.%fZ'),
            interval_minutes
        ),
        256
    )
)
WHERE notification_input_fingerprint IS NULL;

ALTER TABLE schedule_push_job
    MODIFY COLUMN notification_input_fingerprint CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'Deterministic notification semantic input SHA-256';

-- Notification action idempotency. The raw Idempotency-Key is intentionally absent.
-- A row is inserted/flushed before mutation to elect one concurrent winner, but the
-- row and mutation commit in the same transaction, so completed_at cannot be left null.
CREATE TABLE schedule_notification_action_receipts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    key_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    member_id BIGINT NOT NULL,
    schedule_id BIGINT NOT NULL,
    action_type VARCHAR(24) NOT NULL,
    result_departed_at DATETIME(6) NULL,
    result_snoozed_until DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_notification_action_key_fingerprint (key_fingerprint),
    INDEX idx_schedule_notification_action_scope (member_id, schedule_id, action_type)
) COMMENT='Durable idempotency receipts for schedule notification actions';

-- Verification: every result must be 0, except case_distinct_fingerprints which must be 1.
SELECT COUNT(*) AS missing_notification_event_key
FROM app_notifications
WHERE logical_event_key IS NULL
   OR JSON_UNQUOTE(JSON_EXTRACT(data_json, '$.logicalEventKey')) <> logical_event_key
   OR JSON_UNQUOTE(JSON_EXTRACT(data_json, '$.recipientMemberId')) <> CAST(member_id AS CHAR);

SELECT COUNT(*) AS duplicate_token_fingerprint_groups
FROM (
    SELECT token_fingerprint
    FROM push_device_token
    GROUP BY token_fingerprint
    HAVING COUNT(*) > 1
) duplicates;

SELECT COUNT(*) AS duplicate_member_device_fingerprint_groups
FROM (
    SELECT member_id, device_fingerprint
    FROM push_device_token
    WHERE device_fingerprint IS NOT NULL
    GROUP BY member_id, device_fingerprint
    HAVING COUNT(*) > 1
) duplicates;

SELECT COUNT(*) AS remaining_raw_opaque_unique_indexes
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'push_device_token'
  AND index_name IN (
      'uk_push_device_token_token',
      'uk_push_device_token_member_device'
  );

SELECT (
    SHA2(CAST('AbC' AS BINARY), 256) <>
    SHA2(CAST('aBc' AS BINARY), 256)
) AS case_distinct_fingerprints;

SELECT COUNT(*) AS incomplete_schedule_notification_action_receipts
FROM schedule_notification_action_receipts
WHERE completed_at IS NULL;
