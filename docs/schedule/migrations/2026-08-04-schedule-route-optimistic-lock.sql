-- Optimistic-lock version for shared schedule routes (MySQL 8.x).
--
-- This migration is additive and idempotent. MySQL backfills every existing row to 0 while
-- adding the NOT NULL column. A later run verifies the exact contract and leaves versions that
-- have already advanced untouched. Stop all API and worker instances before applying DDL.

DROP PROCEDURE IF EXISTS assert_schedule_route_version_preconditions;
DELIMITER //
CREATE PROCEDURE assert_schedule_route_version_preconditions()
BEGIN
    IF (
        SELECT COUNT(*)
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_routes'
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'schedule route version migration blocked: schedule_routes is absent';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'application_schema_migrations'
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'schedule route version migration blocked: migration marker table is absent';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_travel_plans'
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'schedule route version migration blocked: schedule_travel_plans is absent';
    END IF;

    -- Earlier API versions did not reject partial or out-of-range coordinates. Do not deploy
    -- the stricter writer on top of rows that it cannot subsequently edit. Operators must first
    -- inspect and explicitly correct those places; this migration never guesses or deletes them.
    IF EXISTS (
        SELECT 1
        FROM schedule_routes
        WHERE ((origin_lat IS NULL) <> (origin_lng IS NULL))
           OR (origin_lat IS NOT NULL AND origin_lat NOT BETWEEN -90 AND 90)
           OR (origin_lng IS NOT NULL AND origin_lng NOT BETWEEN -180 AND 180)
           OR ((destination_lat IS NULL) <> (destination_lng IS NULL))
           OR (destination_lat IS NOT NULL AND destination_lat NOT BETWEEN -90 AND 90)
           OR (destination_lng IS NOT NULL AND destination_lng NOT BETWEEN -180 AND 180)
        LIMIT 1
    ) OR EXISTS (
        SELECT 1
        FROM schedule_travel_plans
        WHERE ((origin_lat IS NULL) <> (origin_lng IS NULL))
           OR (origin_lat IS NOT NULL AND origin_lat NOT BETWEEN -90 AND 90)
           OR (origin_lng IS NOT NULL AND origin_lng NOT BETWEEN -180 AND 180)
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'schedule route version migration blocked: invalid legacy coordinates require explicit cleanup';
    END IF;

    -- An exact existing column is a valid rerun. Refuse to reinterpret an incompatible column.
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_routes'
          AND column_name = 'version'
    ) AND (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_routes'
          AND column_name = 'version'
          AND data_type = 'bigint'
          AND column_type = 'bigint'
          AND is_nullable = 'NO'
          AND column_default = '0'
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'schedule route version migration blocked: existing version column is incompatible';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM application_schema_migrations
        WHERE version = '2026-08-04-schedule-route-optimistic-lock-v1'
          AND description <>
              'Optimistic-lock version for shared schedule routes'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'schedule route version migration blocked: marker description is incompatible';
    END IF;
END//
DELIMITER ;

CALL assert_schedule_route_version_preconditions();
DROP PROCEDURE assert_schedule_route_version_preconditions;

-- MySQL does not consistently support ADD COLUMN IF NOT EXISTS across deployed 8.x patch
-- versions, so information_schema selects either the additive ALTER or an explicit no-op.
SET @schedule_route_version_exists = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'schedule_routes'
      AND column_name = 'version'
);
SET @schedule_route_version_ddl = IF(
    @schedule_route_version_exists = 0,
    'ALTER TABLE schedule_routes ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT ''Optimistic lock version'' AFTER id',
    'DO 0'
);
PREPARE schedule_route_version_statement FROM @schedule_route_version_ddl;
EXECUTE schedule_route_version_statement;
DEALLOCATE PREPARE schedule_route_version_statement;

DROP PROCEDURE IF EXISTS assert_schedule_route_version_postconditions;
DELIMITER //
CREATE PROCEDURE assert_schedule_route_version_postconditions()
BEGIN
    IF (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_routes'
          AND column_name = 'version'
          AND data_type = 'bigint'
          AND column_type = 'bigint'
          AND is_nullable = 'NO'
          AND column_default = '0'
    ) <> 1 OR EXISTS (
        SELECT 1
        FROM schedule_routes
        WHERE version IS NULL
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'schedule route version migration verification failed: column contract is absent';
    END IF;
END//
DELIMITER ;

CALL assert_schedule_route_version_postconditions();
DROP PROCEDURE assert_schedule_route_version_postconditions;

-- Preserve the first verified application timestamp and make sequential reapplication a no-op.
INSERT INTO application_schema_migrations(version, description, applied_at)
SELECT
    '2026-08-04-schedule-route-optimistic-lock-v1',
    'Optimistic-lock version for shared schedule routes',
    CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (
    SELECT 1
    FROM application_schema_migrations
    WHERE version = '2026-08-04-schedule-route-optimistic-lock-v1'
);

SELECT COUNT(*) AS schedule_route_version_marker_count
FROM application_schema_migrations
WHERE version = '2026-08-04-schedule-route-optimistic-lock-v1';

SELECT column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'schedule_routes'
  AND column_name = 'version';

SELECT COUNT(*) AS invalid_schedule_route_coordinate_count
FROM schedule_routes
WHERE ((origin_lat IS NULL) <> (origin_lng IS NULL))
   OR (origin_lat IS NOT NULL AND origin_lat NOT BETWEEN -90 AND 90)
   OR (origin_lng IS NOT NULL AND origin_lng NOT BETWEEN -180 AND 180)
   OR ((destination_lat IS NULL) <> (destination_lng IS NULL))
   OR (destination_lat IS NOT NULL AND destination_lat NOT BETWEEN -90 AND 90)
   OR (destination_lng IS NOT NULL AND destination_lng NOT BETWEEN -180 AND 180);

SELECT COUNT(*) AS invalid_travel_plan_origin_coordinate_count
FROM schedule_travel_plans
WHERE ((origin_lat IS NULL) <> (origin_lng IS NULL))
   OR (origin_lat IS NOT NULL AND origin_lat NOT BETWEEN -90 AND 90)
   OR (origin_lng IS NOT NULL AND origin_lng NOT BETWEEN -180 AND 180);
