-- Per-member departure alert presentation mode (MySQL 8.x).
--
-- This is an additive, backward-compatible migration. Existing rows and writers retain the
-- STANDARD behavior. New binaries may opt an individual route/travel plan into ALARM only after
-- both columns and the verified marker exist.

DROP PROCEDURE IF EXISTS assert_departure_alarm_mode_preconditions;
DELIMITER //
CREATE PROCEDURE assert_departure_alarm_mode_preconditions()
BEGIN
    IF (
        SELECT COUNT(*)
        FROM application_schema_migrations
        WHERE version IN (
            '2026-07-24-push-reliability-v4',
            '2026-07-26-apple-token-lifecycle-v1',
            '2026-07-26-account-deletion-v1',
            '2026-07-27-schedule-calendar-cache-revision-v1'
        )
    ) <> 4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'departure alarm mode migration blocked: required predecessor marker is absent';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name IN ('schedule_routes', 'schedule_travel_plans')
          AND column_name = 'alert_mode'
    ) <> 0 OR EXISTS (
        SELECT 1
        FROM application_schema_migrations
        WHERE version = '2026-07-29-departure-alarm-mode-v1'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'departure alarm mode migration blocked: partial or already-applied schema requires inspection';
    END IF;
END//
DELIMITER ;

CALL assert_departure_alarm_mode_preconditions();
DROP PROCEDURE assert_departure_alarm_mode_preconditions;

ALTER TABLE schedule_routes
    ADD COLUMN alert_mode VARCHAR(20) NOT NULL DEFAULT 'STANDARD'
        COMMENT 'STANDARD or ALARM departure alert mode'
        AFTER notification_interval_minutes;

ALTER TABLE schedule_travel_plans
    ADD COLUMN alert_mode VARCHAR(20) NOT NULL DEFAULT 'STANDARD'
        COMMENT 'STANDARD or ALARM member alert mode'
        AFTER notification_interval_minutes;

DROP PROCEDURE IF EXISTS assert_departure_alarm_mode_postconditions;
DELIMITER //
CREATE PROCEDURE assert_departure_alarm_mode_postconditions()
BEGIN
    IF (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name IN ('schedule_routes', 'schedule_travel_plans')
          AND column_name = 'alert_mode'
          AND data_type = 'varchar'
          AND character_maximum_length = 20
          AND is_nullable = 'NO'
          AND column_default = 'STANDARD'
    ) <> 2 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'departure alarm mode migration verification failed: column contract is absent';
    END IF;

    IF EXISTS (
        SELECT 1 FROM schedule_routes
        WHERE alert_mode NOT IN ('STANDARD', 'ALARM')
        LIMIT 1
    ) OR EXISTS (
        SELECT 1 FROM schedule_travel_plans
        WHERE alert_mode NOT IN ('STANDARD', 'ALARM')
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'departure alarm mode migration verification failed: invalid alert mode exists';
    END IF;
END//
DELIMITER ;

CALL assert_departure_alarm_mode_postconditions();
DROP PROCEDURE assert_departure_alarm_mode_postconditions;

INSERT INTO application_schema_migrations(version, description, applied_at)
VALUES (
    '2026-07-29-departure-alarm-mode-v1',
    'Per-member STANDARD or ALARM departure alert presentation mode',
    CURRENT_TIMESTAMP(6)
);

SELECT COUNT(*) AS departure_alarm_mode_marker_count
FROM application_schema_migrations
WHERE version = '2026-07-29-departure-alarm-mode-v1';

SELECT table_name, column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN ('schedule_routes', 'schedule_travel_plans')
  AND column_name = 'alert_mode'
ORDER BY table_name;
