-- Device-scoped ActivityKit appearance snapshot (MySQL 8.x).
--
-- Apply this additive migration before deploying the backend that maps the nullable appearance
-- column. Existing clients omit the field and remain valid; existing rows intentionally stay NULL
-- so the widget can retain its legacy system-trait fallback until the device registers a snapshot.

DROP PROCEDURE IF EXISTS assert_live_activity_appearance_preconditions;
DELIMITER //
CREATE PROCEDURE assert_live_activity_appearance_preconditions()
BEGIN
    IF (
        SELECT COUNT(*)
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name IN ('application_schema_migrations', 'live_activity_start_tokens')
    ) <> 2 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'live activity appearance migration blocked: required tables are absent';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM application_schema_migrations
        WHERE version = '2026-08-06-live-activity-delivery-v1'
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'live activity appearance migration blocked: predecessor marker is absent';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'live_activity_start_tokens'
          AND column_name = 'appearance'
    ) OR EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'live_activity_start_tokens'
          AND constraint_name = 'chk_live_activity_start_token_appearance'
    ) OR EXISTS (
        SELECT 1
        FROM application_schema_migrations
        WHERE version = '2026-08-06-live-activity-appearance-v1'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'live activity appearance migration blocked: partial or applied schema requires inspection';
    END IF;
END//
DELIMITER ;

CALL assert_live_activity_appearance_preconditions();
DROP PROCEDURE assert_live_activity_appearance_preconditions;

ALTER TABLE live_activity_start_tokens
    ADD COLUMN appearance VARCHAR(10) NULL
        COMMENT 'Device-scoped ActivityKit appearance snapshot: LIGHT or DARK'
        AFTER token_generation,
    ADD CONSTRAINT chk_live_activity_start_token_appearance CHECK (
        appearance IS NULL OR appearance IN ('LIGHT', 'DARK')
    );

DROP PROCEDURE IF EXISTS assert_live_activity_appearance_postconditions;
DELIMITER //
CREATE PROCEDURE assert_live_activity_appearance_postconditions()
BEGIN
    IF (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'live_activity_start_tokens'
          AND column_name = 'appearance'
          AND data_type = 'varchar'
          AND character_maximum_length = 10
          AND is_nullable = 'YES'
          AND column_default IS NULL
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'live activity appearance migration verification failed: column contract is absent';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM information_schema.table_constraints tc
        JOIN information_schema.check_constraints cc
          ON cc.constraint_schema = tc.constraint_schema
         AND cc.constraint_name = tc.constraint_name
        WHERE tc.constraint_schema = DATABASE()
          AND tc.table_name = 'live_activity_start_tokens'
          AND tc.constraint_name = 'chk_live_activity_start_token_appearance'
          AND tc.constraint_type = 'CHECK'
          AND UPPER(cc.check_clause) LIKE '%APPEARANCE%IS NULL%'
          AND UPPER(cc.check_clause) LIKE '%LIGHT%'
          AND UPPER(cc.check_clause) LIKE '%DARK%'
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'live activity appearance migration verification failed: check constraint is absent';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM live_activity_start_tokens
        WHERE appearance IS NOT NULL
          AND appearance NOT IN ('LIGHT', 'DARK')
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'live activity appearance migration verification failed: invalid value exists';
    END IF;
END//
DELIMITER ;

CALL assert_live_activity_appearance_postconditions();
DROP PROCEDURE assert_live_activity_appearance_postconditions;

INSERT INTO application_schema_migrations(version, description, applied_at)
VALUES (
    '2026-08-06-live-activity-appearance-v1',
    'Device-scoped light or dark ActivityKit appearance snapshot',
    CURRENT_TIMESTAMP(6)
);

SELECT COUNT(*) AS live_activity_appearance_marker_count
FROM application_schema_migrations
WHERE version = '2026-08-06-live-activity-appearance-v1';

SELECT column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'live_activity_start_tokens'
  AND column_name = 'appearance';
