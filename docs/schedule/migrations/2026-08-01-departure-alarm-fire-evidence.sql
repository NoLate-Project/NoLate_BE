-- Durable native departure-alarm fire evidence (MySQL 8.x).
-- Stop all application instances before running. MySQL DDL commits implicitly.

DROP PROCEDURE IF EXISTS assert_departure_alarm_fire_evidence_preconditions;
DELIMITER //
CREATE PROCEDURE assert_departure_alarm_fire_evidence_preconditions()
BEGIN
    IF (
        SELECT COUNT(*) FROM application_schema_migrations
        WHERE version = '2026-07-31-push-eta-trust-v1'
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'alarm fire evidence migration blocked: predecessor marker is absent';
    END IF;

    IF (
        SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_sync_state'
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'alarm fire evidence migration blocked: desired-state table is absent';
    END IF;

    IF EXISTS (
        SELECT 1 FROM application_schema_migrations
        WHERE version = '2026-08-01-departure-alarm-fire-evidence-v1'
    ) OR EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_fire_events'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'alarm fire evidence migration blocked: partial or applied schema requires inspection';
    END IF;
END//
DELIMITER ;

CALL assert_departure_alarm_fire_evidence_preconditions();
DROP PROCEDURE assert_departure_alarm_fire_evidence_preconditions;

CREATE TABLE departure_alarm_fire_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    client_event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    device_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    alarm_id VARCHAR(100) NOT NULL,
    schedule_id BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    desired_generation_at_receipt BIGINT NOT NULL,
    desired_operation_at_receipt VARCHAR(16) NOT NULL,
    generation_relation VARCHAR(16) NOT NULL,
    scheduled_for DATETIME(6) NOT NULL,
    source_trigger_at DATETIME(6) NULL,
    client_occurred_at DATETIME(6) NOT NULL,
    timing_basis VARCHAR(24) NOT NULL,
    fire_delay_seconds BIGINT NOT NULL,
    server_recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_departure_alarm_fire_member_event (member_id, client_event_id),
    UNIQUE KEY uk_departure_alarm_fire_member_device_trigger (
        member_id, device_fingerprint, alarm_id, generation, scheduled_for
    ),
    INDEX idx_departure_alarm_fire_recorded_at (server_recorded_at, id),
    INDEX idx_departure_alarm_fire_member (member_id, id),
    INDEX idx_departure_alarm_fire_schedule (schedule_id, server_recorded_at),
    CONSTRAINT chk_departure_alarm_fire_generation
        CHECK (generation BETWEEN 0 AND 9007199254740991),
    CONSTRAINT chk_departure_alarm_fire_desired_generation
        CHECK (
            desired_generation_at_receipt BETWEEN generation AND 9007199254740991
        ),
    CONSTRAINT chk_departure_alarm_fire_operation
        CHECK (desired_operation_at_receipt IN ('UPSERT', 'CANCEL')),
    CONSTRAINT chk_departure_alarm_fire_timing_basis
        CHECK (timing_basis IN ('EXACT_CALLBACK', 'OBSERVED_ALERTING', 'INFERRED_OS_DELIVERY')),
    CONSTRAINT chk_departure_alarm_fire_relation
        CHECK (
            (generation_relation = 'CURRENT' AND generation = desired_generation_at_receipt) OR
            (generation_relation = 'STALE' AND generation < desired_generation_at_receipt)
        )
) COMMENT='Authenticated durable evidence of actual native departure-alarm execution';

DROP PROCEDURE IF EXISTS assert_departure_alarm_fire_evidence_postconditions;
DELIMITER //
CREATE PROCEDURE assert_departure_alarm_fire_evidence_postconditions()
BEGIN
    IF (
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_fire_events'
    ) <> 16 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'alarm fire evidence migration verification failed';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM (
            SELECT index_name
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'departure_alarm_fire_events'
            GROUP BY index_name
            HAVING
                (index_name = 'PRIMARY'
                    AND MIN(non_unique) = 0
                    AND GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'id')
                OR (index_name = 'uk_departure_alarm_fire_member_event'
                    AND MIN(non_unique) = 0
                    AND GROUP_CONCAT(column_name ORDER BY seq_in_index) =
                        'member_id,client_event_id')
                OR (index_name = 'uk_departure_alarm_fire_member_device_trigger'
                    AND MIN(non_unique) = 0
                    AND GROUP_CONCAT(column_name ORDER BY seq_in_index) =
                        'member_id,device_fingerprint,alarm_id,generation,scheduled_for')
                OR (index_name = 'idx_departure_alarm_fire_recorded_at'
                    AND MIN(non_unique) = 1
                    AND GROUP_CONCAT(column_name ORDER BY seq_in_index) =
                        'server_recorded_at,id')
                OR (index_name = 'idx_departure_alarm_fire_member'
                    AND MIN(non_unique) = 1
                    AND GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'member_id,id')
                OR (index_name = 'idx_departure_alarm_fire_schedule'
                    AND MIN(non_unique) = 1
                    AND GROUP_CONCAT(column_name ORDER BY seq_in_index) =
                        'schedule_id,server_recorded_at')
        ) verified_indexes
    ) <> 6 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'alarm fire evidence index verification failed';
    END IF;
END//
DELIMITER ;

CALL assert_departure_alarm_fire_evidence_postconditions();
DROP PROCEDURE assert_departure_alarm_fire_evidence_postconditions;

INSERT INTO application_schema_migrations(version, description, applied_at)
VALUES (
    '2026-08-01-departure-alarm-fire-evidence-v1',
    'Durable authenticated native departure-alarm fire evidence',
    CURRENT_TIMESTAMP(6)
);

SELECT COUNT(*) AS departure_alarm_fire_evidence_marker_count
FROM application_schema_migrations
WHERE version = '2026-08-01-departure-alarm-fire-evidence-v1';
