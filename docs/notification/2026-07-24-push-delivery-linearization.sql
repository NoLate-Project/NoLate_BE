-- Apply only after:
--   1) 2026-07-24-push-deliveries.sql
--   2) 2026-07-24-push-delivery-followup.sql
-- and before deploying 751cbd4 + 10bd1da + 4343d0e + the reliability-v4
-- application together.
--
-- ALL old API and worker instances must be stopped before this script begins. DDL commits
-- implicitly in MySQL; the marker at the bottom is intentionally absent until every
-- postcondition succeeds. The new application refuses startup without that marker.
--
-- These commits have never been deployed. Any push_deliveries row, schedule-push-job inbox
-- key, legacy schedule push job, or legacy push token proves that preview/legacy writers need
-- an explicit drain.
-- In particular, every pre-v4 push token must be removed by an operator after quiesce so
-- no generation-less session can retain a provider endpoint across the session fence.
-- This script never deletes those rows; it stops instead of silently deleting evidence.

DROP PROCEDURE IF EXISTS assert_push_linearization_preconditions;
DELIMITER //
CREATE PROCEDURE assert_push_linearization_preconditions()
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND (
              (table_name = 'app_notifications' AND column_name = 'logical_event_key')
              OR (table_name = 'push_device_token' AND column_name = 'token_fingerprint')
              OR (table_name = 'member' AND column_name = 'session_generation')
          )
        LIMIT 1
    ) OR EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name IN (
              'schedule_notification_action_receipts',
              'application_schema_migrations'
          )
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'push linearization migration blocked: inspect partial or preview schema first';
    END IF;

    -- Mandatory first-deployment fence: old JWTs have no monotonic session-generation
    -- claim. Even a unique, apparently current token therefore cannot be carried safely
    -- into v4. The operator must explicitly drain every row after quiescing all writers.
    IF EXISTS (SELECT 1 FROM push_device_token LIMIT 1) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'push linearization migration blocked: drain all legacy push tokens first';
    END IF;

    IF EXISTS (SELECT 1 FROM push_deliveries LIMIT 1) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'push linearization migration blocked: drain/inspect legacy push_deliveries first';
    END IF;

    -- A legacy job has only the old timing tuple, while the v4 runtime fingerprint also binds
    -- title, destination, member-specific origin/route/mode and notification policy. Guessing a
    -- partial hash would make the first identical PUT look like a semantic edit. Jobs therefore
    -- require an explicit evidence-preserving drain and are rebuilt by the new application from
    -- the authoritative schedule/travel-plan rows with the full runtime fingerprint.
    IF EXISTS (SELECT 1 FROM schedule_push_job LIMIT 1) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'push linearization migration blocked: drain legacy schedule push jobs first';
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

    IF EXISTS (
        SELECT 1
        FROM push_device_token
        GROUP BY SHA2(CAST(token AS BINARY), 256)
        HAVING COUNT(*) > 1
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'push linearization migration blocked: drain duplicate token fingerprints first';
    END IF;

    -- Installation identity is global across accounts and deliberately ignores platform.
    IF EXISTS (
        SELECT 1
        FROM push_device_token
        WHERE device_id IS NOT NULL
          AND CHAR_LENGTH(device_id) > 0
        GROUP BY SHA2(CAST(device_id AS BINARY), 256)
        HAVING COUNT(*) > 1
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'push linearization migration blocked: resolve duplicate device fingerprints first';
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
    WHEN JSON_VALID(data_json) THEN CASE
        -- JSON_SET cannot attach object members to a valid scalar/array document. Legacy
        -- non-object payloads are invalid for Map<String, String>, so replace them with the
        -- minimum canonical account binding instead of silently marking the migration complete.
        WHEN JSON_TYPE(data_json) = 'OBJECT' THEN JSON_SET(
            data_json,
            '$.logicalEventKey', logical_event_key,
            '$.recipientMemberId', CAST(member_id AS CHAR)
        )
        ELSE JSON_OBJECT(
            'logicalEventKey', logical_event_key,
            'recipientMemberId', CAST(member_id AS CHAR)
        )
    END
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

-- Existing inbox rows are intentionally not expanded to devices during or after rollout.
-- New code creates OPEN and FROZEN in one business transaction; the explicit states allow
-- a committed snapshot (including a zero-device snapshot) to be distinguished from inbox.
ALTER TABLE app_notifications
    ADD COLUMN manifest_state VARCHAR(24) NOT NULL DEFAULT 'INBOX_ONLY'
        COMMENT 'INBOX_ONLY, OPEN, or immutable FROZEN recipient snapshot' AFTER read_at,
    ADD COLUMN manifest_recipient_count INT NOT NULL DEFAULT 0
        COMMENT 'Frozen delivery row count, including zero-device events' AFTER manifest_state,
    ADD COLUMN manifest_frozen_at DATETIME(6) NULL
        COMMENT 'Recipient snapshot linearization time' AFTER manifest_recipient_count,
    ADD COLUMN dispatch_status VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUIRED'
        COMMENT 'NOT_REQUIRED, PENDING, PROCESSING, COMPLETED, or FAILED'
        AFTER manifest_frozen_at,
    ADD COLUMN dispatch_attempt_count INT NOT NULL DEFAULT 0
        COMMENT 'Monotonic durable outbox lease epochs' AFTER dispatch_status,
    ADD COLUMN dispatch_failure_count INT NOT NULL DEFAULT 0
        COMMENT 'Actual retry-budget failures; expected deferrals do not increment'
        AFTER dispatch_attempt_count,
    ADD COLUMN next_dispatch_at DATETIME(6) NULL
        COMMENT 'Next bounded drainer eligibility time' AFTER dispatch_failure_count,
    ADD COLUMN dispatch_locked_by VARCHAR(100) NULL
        COMMENT 'Outbox drainer lease owner' AFTER next_dispatch_at,
    ADD COLUMN dispatch_locked_at DATETIME(6) NULL
        COMMENT 'Outbox drainer lease time' AFTER dispatch_locked_by,
    ADD COLUMN dispatch_completed_at DATETIME(6) NULL
        COMMENT 'Terminal drainer time' AFTER dispatch_locked_at,
    ADD COLUMN dispatch_failure_reason VARCHAR(500) NULL
        COMMENT 'Sanitized last drainer outcome' AFTER dispatch_completed_at,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0
        COMMENT 'Optimistic lock version' AFTER dispatch_failure_reason,
    ADD INDEX idx_app_notifications_dispatch_due
        (dispatch_status, next_dispatch_at, id),
    ADD INDEX idx_app_notifications_dispatch_lease
        (dispatch_status, dispatch_locked_at, id);

-- Case-sensitive one-way identity. Raw token/deviceId columns remain for provider/client
-- use but are deliberately not indexed, so duplicate-key messages contain only SHA-256.
ALTER TABLE push_device_token
    ADD COLUMN token_fingerprint VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL AFTER token,
    ADD COLUMN device_fingerprint VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL AFTER token_fingerprint,
    ADD COLUMN ownership_version BIGINT NOT NULL DEFAULT 0 AFTER device_fingerprint;

UPDATE push_device_token
SET token_fingerprint = LOWER(SHA2(CAST(token AS BINARY), 256)),
    device_fingerprint = CASE
        WHEN device_id IS NULL OR CHAR_LENGTH(device_id) = 0 THEN NULL
        ELSE LOWER(SHA2(CAST(device_id AS BINARY), 256))
    END;

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

    -- A preview build may have created the member-scoped fingerprint index. It is unsafe:
    -- the same installation could then remain owned by both account A and account B.
    IF EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'push_device_token'
          AND index_name = 'uk_push_device_token_member_device_fingerprint'
    ) THEN
        ALTER TABLE push_device_token
            DROP INDEX uk_push_device_token_member_device_fingerprint;
    END IF;

    -- Preview/operator-created raw unique indexes may use arbitrary names. Drop every remaining
    -- non-primary unique index whose column composition contains token or device_id; checking
    -- only the three historical names could silently preserve case-insensitive identity.
    SELECT GROUP_CONCAT(
        CONCAT('DROP INDEX `', REPLACE(index_name, '`', '``'), '`')
        ORDER BY index_name
        SEPARATOR ', '
    )
    INTO @raw_push_unique_drop_clauses
    FROM (
        SELECT index_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'push_device_token'
          AND non_unique = 0
          AND index_name <> 'PRIMARY'
        GROUP BY index_name
        HAVING SUM(
            CASE WHEN column_name IN ('token', 'device_id') THEN 1 ELSE 0 END
        ) > 0
    ) raw_push_unique_indexes;

    IF @raw_push_unique_drop_clauses IS NOT NULL THEN
        SET @drop_raw_push_unique_sql =
            CONCAT('ALTER TABLE push_device_token ', @raw_push_unique_drop_clauses);
        PREPARE drop_raw_push_unique_stmt FROM @drop_raw_push_unique_sql;
        EXECUTE drop_raw_push_unique_stmt;
        DEALLOCATE PREPARE drop_raw_push_unique_stmt;
    END IF;
END//
DELIMITER ;

CALL drop_push_raw_unique_indexes();
DROP PROCEDURE drop_push_raw_unique_indexes;

ALTER TABLE push_device_token
    MODIFY COLUMN token_fingerprint VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY COLUMN device_fingerprint VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_push_device_token_token_fingerprint (token_fingerprint),
    ADD UNIQUE KEY uk_push_device_token_device_fingerprint (device_fingerprint);

-- A signed monotonic generation, not second-precision JWT iat, is the session fence.
-- Existing access/refresh tokens have no generation claim and must reauthenticate after
-- deployment. Generation 0 is the legacy/empty migration fence; the first explicit
-- post-deploy login atomically opens generation 1.
ALTER TABLE `member`
    ADD COLUMN session_generation BIGINT NOT NULL DEFAULT 0
        COMMENT 'Monotonic logout/session invalidation generation'
        AFTER tokens_valid_after;

-- Per-device manifest stores the exact ownership snapshot claimed before provider I/O.
-- The precondition guarantees this table is empty, so adding NOT NULL columns is safe.
ALTER TABLE push_deliveries
    CHANGE COLUMN device_id device_fingerprint VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'One-way client device fingerprint',
    ADD COLUMN token_fingerprint VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL AFTER device_token_id,
    ADD COLUMN token_ownership_version BIGINT NOT NULL AFTER token_fingerprint;

-- Meaningful-edit generation fence and confirmed-vs-uncertain schedule semantics.
ALTER TABLE schedule_push_job
    ADD COLUMN notification_input_fingerprint VARCHAR(64)
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

-- The mandatory precondition leaves this table empty. New owner and participant jobs are
-- reconstructed from authoritative domain rows and always insert the full runtime fingerprint;
-- no partial legacy value is silently promoted to a valid semantic identity.
ALTER TABLE schedule_push_job
    MODIFY COLUMN notification_input_fingerprint VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'Deterministic notification semantic input SHA-256';

-- Notification action idempotency. The raw Idempotency-Key is intentionally absent.
-- A row is inserted/flushed before mutation to elect one concurrent winner, but the
-- row and mutation commit in the same transaction, so completed_at cannot be left null.
CREATE TABLE schedule_notification_action_receipts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    key_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
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

-- Fail closed before writing the deployment marker. These messages contain only
-- classification/count semantics and never a raw token or device id.
DROP PROCEDURE IF EXISTS assert_push_linearization_postconditions;
DELIMITER //
CREATE PROCEDURE assert_push_linearization_postconditions()
BEGIN
    IF EXISTS (SELECT 1 FROM push_device_token LIMIT 1) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'push linearization verification failed: legacy push token drain';
    END IF;

    IF EXISTS (SELECT 1 FROM schedule_push_job LIMIT 1) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'push linearization verification failed: legacy schedule push job drain';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM app_notifications
        WHERE logical_event_key IS NULL
           OR JSON_EXTRACT(data_json, '$.logicalEventKey') IS NULL
           OR JSON_EXTRACT(data_json, '$.recipientMemberId') IS NULL
           OR JSON_UNQUOTE(JSON_EXTRACT(data_json, '$.logicalEventKey')) <> logical_event_key
           OR JSON_UNQUOTE(JSON_EXTRACT(data_json, '$.recipientMemberId')) <> CAST(member_id AS CHAR)
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'push linearization verification failed: canonical notification binding';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM app_notifications
        WHERE manifest_state NOT IN ('INBOX_ONLY', 'OPEN', 'FROZEN')
           OR dispatch_status NOT IN ('NOT_REQUIRED', 'PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')
           OR manifest_recipient_count < 0
           OR dispatch_attempt_count < 0
           OR dispatch_failure_count < 0
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'push linearization verification failed: notification manifest state';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM push_device_token
        GROUP BY token_fingerprint
        HAVING COUNT(*) > 1
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'push linearization verification failed: duplicate token fingerprints';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM push_device_token
        WHERE device_fingerprint IS NOT NULL
        GROUP BY device_fingerprint
        HAVING COUNT(*) > 1
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'push linearization verification failed: duplicate global device fingerprints';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'push_device_token'
          AND index_name IN (
              'uk_push_device_token_token',
              'uk_push_device_token_member_device',
              'uk_push_device_token_member_device_fingerprint'
          )
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'push linearization verification failed: raw/member-scoped opaque index remains';
    END IF;

    IF EXISTS (
        SELECT index_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'push_device_token'
          AND non_unique = 0
          AND index_name <> 'PRIMARY'
        GROUP BY index_name
        HAVING SUM(
            CASE WHEN column_name IN ('token', 'device_id') THEN 1 ELSE 0 END
        ) > 0
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'push linearization verification failed: arbitrary raw opaque unique index remains';
    END IF;

    IF (
        SELECT COUNT(DISTINCT index_name)
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'push_device_token'
          AND index_name IN (
              'uk_push_device_token_token_fingerprint',
              'uk_push_device_token_device_fingerprint'
          )
    ) <> 2 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'push linearization verification failed: global fingerprint indexes';
    END IF;

    IF SHA2(CAST('AbC' AS BINARY), 256) = SHA2(CAST('aBc' AS BINARY), 256) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'push linearization verification failed: opaque case sensitivity';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM schedule_notification_action_receipts
        WHERE completed_at IS NULL
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'push linearization verification failed: incomplete action receipt';
    END IF;
END//
DELIMITER ;

CALL assert_push_linearization_postconditions();
DROP PROCEDURE assert_push_linearization_postconditions;

CREATE TABLE IF NOT EXISTS application_schema_migrations (
    version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    description VARCHAR(255) NOT NULL,
    applied_at DATETIME(6) NOT NULL,
    PRIMARY KEY (version)
) COMMENT='Manually verified production schema versions';

INSERT INTO application_schema_migrations(version, description, applied_at)
VALUES (
    '2026-07-24-push-reliability-v4',
    'Frozen push manifests, durable outbox, global installation ownership, session generation',
    CURRENT_TIMESTAMP(6)
);

-- Human-readable verification: every count must be 0 and the marker count must be 1.
SELECT COUNT(*) AS remaining_legacy_push_tokens
FROM push_device_token;

SELECT COUNT(*) AS remaining_legacy_schedule_push_jobs
FROM schedule_push_job;

SELECT COUNT(*) AS missing_notification_event_key
FROM app_notifications
WHERE logical_event_key IS NULL
   OR JSON_EXTRACT(data_json, '$.logicalEventKey') IS NULL
   OR JSON_EXTRACT(data_json, '$.recipientMemberId') IS NULL
   OR JSON_UNQUOTE(JSON_EXTRACT(data_json, '$.logicalEventKey')) <> logical_event_key
   OR JSON_UNQUOTE(JSON_EXTRACT(data_json, '$.recipientMemberId')) <> CAST(member_id AS CHAR);

SELECT COUNT(*) AS duplicate_token_fingerprint_groups
FROM (
    SELECT token_fingerprint
    FROM push_device_token
    GROUP BY token_fingerprint
    HAVING COUNT(*) > 1
) duplicates;

SELECT COUNT(*) AS duplicate_global_device_fingerprint_groups
FROM (
    SELECT device_fingerprint
    FROM push_device_token
    WHERE device_fingerprint IS NOT NULL
    GROUP BY device_fingerprint
    HAVING COUNT(*) > 1
) duplicates;

SELECT COUNT(*) AS remaining_raw_opaque_unique_indexes
FROM (
    SELECT index_name
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'push_device_token'
      AND non_unique = 0
      AND index_name <> 'PRIMARY'
    GROUP BY index_name
    HAVING SUM(
        CASE WHEN column_name IN ('token', 'device_id') THEN 1 ELSE 0 END
    ) > 0
) raw_unique_indexes;

SELECT (
    SHA2(CAST('AbC' AS BINARY), 256) <>
    SHA2(CAST('aBc' AS BINARY), 256)
) AS case_distinct_fingerprints;

SELECT COUNT(*) AS incomplete_schedule_notification_action_receipts
FROM schedule_notification_action_receipts
WHERE completed_at IS NULL;

SELECT COUNT(*) AS missing_required_schema_marker
FROM application_schema_migrations
WHERE version = '2026-07-24-push-reliability-v4'
HAVING COUNT(*) <> 1;
