-- MySQL 8.x additive migration. Stop API/worker instances before applying.
-- Legacy reasons remain NULL: do not infer automatic cancellation from ETA history.
-- Preconditions, DDL, verification and marker share one CALL so SOURCE cannot
-- continue to a success marker after a failed precondition.
DROP PROCEDURE IF EXISTS migrate_departure_alarm_cancellation_reason;
DELIMITER //
CREATE PROCEDURE migrate_departure_alarm_cancellation_reason()
BEGIN
    IF (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name IN
            ('application_schema_migrations', 'departure_alarm_sync_state')) <> 2 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Alarm cancellation migration blocked: required tables are absent';
    END IF;
    IF (SELECT COUNT(*) FROM application_schema_migrations
        WHERE version = '2026-08-04-departure-alarm-plan-v2') <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Alarm cancellation migration blocked: plan v2 marker is absent';
    END IF;
    IF EXISTS (SELECT 1 FROM application_schema_migrations
        WHERE version = '2026-10-05-departure-alarm-cancellation-reason-v1'
          AND description <> 'Persist automatic and terminal departure alarm cancellation reasons') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Alarm cancellation migration blocked: incompatible marker';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'departure_alarm_sync_state' AND column_name = 'cancellation_reason') THEN
        ALTER TABLE departure_alarm_sync_state ADD COLUMN cancellation_reason VARCHAR(40) NULL
            COMMENT 'Server-only cancellation cause; NULL legacy reasons never auto-resume';
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'departure_alarm_sync_state' AND column_name = 'cancellation_reason'
        AND data_type = 'varchar' AND character_maximum_length = 40 AND is_nullable = 'YES') <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Alarm cancellation migration blocked: incompatible column';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema = DATABASE()
        AND table_name = 'departure_alarm_sync_state' AND constraint_name = 'chk_departure_alarm_cancellation_reason') THEN
        ALTER TABLE departure_alarm_sync_state ADD CONSTRAINT chk_departure_alarm_cancellation_reason CHECK (
            cancellation_reason IS NULL OR (operation = 'CANCEL' AND cancellation_reason IN
                ('EXPLICIT_OR_TERMINAL', 'PAST_CONFIGURED_ESTIMATE', 'TRANSIT_TRANSFER_FAILURE'))
        );
    END IF;
    INSERT INTO application_schema_migrations(version, description, applied_at)
    VALUES ('2026-10-05-departure-alarm-cancellation-reason-v1',
        'Persist automatic and terminal departure alarm cancellation reasons', CURRENT_TIMESTAMP(6))
    ON DUPLICATE KEY UPDATE version = VALUES(version);
END//
DELIMITER ;
CALL migrate_departure_alarm_cancellation_reason();
DROP PROCEDURE migrate_departure_alarm_cancellation_reason;
