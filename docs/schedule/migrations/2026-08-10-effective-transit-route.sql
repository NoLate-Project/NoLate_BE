-- Persist the transit itinerary actually used by the latest ETA decision (MySQL 8.x).
-- Apply before deploying the backend entity mapping. DDL commits implicitly.

DROP PROCEDURE IF EXISTS assert_effective_transit_route_preconditions;
DELIMITER //
CREATE PROCEDURE assert_effective_transit_route_preconditions()
BEGIN
    IF (
        SELECT COUNT(*)
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name IN ('application_schema_migrations', 'schedule_push_job')
    ) <> 2 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'effective transit route migration blocked: required tables are absent';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM application_schema_migrations
        WHERE version = '2026-07-31-push-eta-trust-v1'
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'effective transit route migration blocked: predecessor marker is absent';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_push_job'
          AND column_name IN (
              'last_effective_transit_route_json',
              'last_eta_refresh_due_at',
              'semantic_catch_up_pending'
          )
    ) OR EXISTS (
        SELECT 1
        FROM application_schema_migrations
        WHERE version = '2026-08-10-effective-transit-route-v1'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'effective transit route migration blocked: partial or applied schema requires inspection';
    END IF;
END//
DELIMITER ;

CALL assert_effective_transit_route_preconditions();
DROP PROCEDURE assert_effective_transit_route_preconditions;

ALTER TABLE schedule_push_job
    ADD COLUMN last_effective_transit_route_json LONGTEXT NULL
        COMMENT 'Latest transit route evaluation and timing provenance JSON'
        AFTER last_eta_route_fingerprint,
    ADD COLUMN last_eta_refresh_due_at DATETIME(6) NULL
        COMMENT 'Immutable next ETA refresh boundary recorded by the latest evaluation'
        AFTER last_checked_at,
    ADD COLUMN semantic_catch_up_pending BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'Whether immutable event completion needs a fresh ETA correction check'
        AFTER next_check_at;

-- The pre-migration worker encoded this durable intent as exactly
-- `last_checked_at + 1 second = next_check_at`. Preserve in-flight corrections while workers are
-- stopped for the migration instead of defaulting those rows to FALSE.
UPDATE schedule_push_job
SET semantic_catch_up_pending = TRUE
WHERE status IN ('ACTIVE', 'PROCESSING')
  AND last_checked_at IS NOT NULL
  AND next_check_at = DATE_ADD(last_checked_at, INTERVAL 1 SECOND);

DROP PROCEDURE IF EXISTS assert_effective_transit_route_postconditions;
DELIMITER //
CREATE PROCEDURE assert_effective_transit_route_postconditions()
BEGIN
    IF (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_push_job'
          AND column_name = 'last_effective_transit_route_json'
          AND data_type = 'longtext'
          AND is_nullable = 'YES'
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'effective transit route migration verification failed: column contract is absent';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_push_job'
          AND column_name = 'last_eta_refresh_due_at'
          AND data_type = 'datetime'
          AND datetime_precision = 6
          AND is_nullable = 'YES'
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'effective transit route migration verification failed: ETA refresh due contract is absent';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_push_job'
          AND column_name = 'semantic_catch_up_pending'
          AND data_type = 'tinyint'
          AND is_nullable = 'NO'
          AND column_default = '0'
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'effective transit route migration verification failed: semantic catch-up contract is absent';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM schedule_push_job
        WHERE status IN ('ACTIVE', 'PROCESSING')
          AND last_checked_at IS NOT NULL
          AND next_check_at = DATE_ADD(last_checked_at, INTERVAL 1 SECOND)
          AND semantic_catch_up_pending = FALSE
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'effective transit route migration verification failed: semantic catch-up backfill is incomplete';
    END IF;
END//
DELIMITER ;

CALL assert_effective_transit_route_postconditions();
DROP PROCEDURE assert_effective_transit_route_postconditions;

INSERT INTO application_schema_migrations(version, description, applied_at)
VALUES (
    '2026-08-10-effective-transit-route-v1',
    'Persist effective transit itinerary, ETA freshness, and immutable-event catch-up state',
    CURRENT_TIMESTAMP(6)
);

SELECT COUNT(*) AS effective_transit_route_marker_count
FROM application_schema_migrations
WHERE version = '2026-08-10-effective-transit-route-v1';

SELECT column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'schedule_push_job'
  AND column_name IN (
      'last_effective_transit_route_json',
      'last_eta_refresh_due_at',
      'semantic_catch_up_pending'
  );
