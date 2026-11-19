-- MySQL 8 manual migration. Apply while API and workers are stopped, before the reroute binary.
-- Existing preparations retain their original saved route; no row is backfilled or deleted.
DROP PROCEDURE IF EXISTS assert_automatic_journey_reroute_preconditions;
DELIMITER //
CREATE PROCEDURE assert_automatic_journey_reroute_preconditions()
BEGIN
    IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
        AND table_name = 'automatic_journey_preparations') <> 1
        OR (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
        AND table_name = 'application_schema_migrations') <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Automatic journey reroute base schema is missing';
    END IF;
    IF (SELECT COUNT(*) FROM application_schema_migrations
        WHERE version = '2026-10-04-automatic-journey-preparations-v1') <> 1
        OR EXISTS (SELECT 1 FROM application_schema_migrations
        WHERE version = '2026-11-19-automatic-journey-reroute-v1') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Automatic journey reroute marker is missing or already applied';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'automatic_journey_preparations'
        AND column_name IN ('parent_session_id', 'source_route_fingerprint', 'reroute_snapshot_json',
            'reroute_input_json', 'reroute_bus_admission_json')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Partial reroute columns exist; inspect before retry';
    END IF;
END//
DELIMITER ;
CALL assert_automatic_journey_reroute_preconditions();
DROP PROCEDURE assert_automatic_journey_reroute_preconditions;

ALTER TABLE automatic_journey_preparations
  ADD COLUMN parent_session_id VARCHAR(36) NULL,
  ADD COLUMN source_route_fingerprint VARCHAR(64) NULL,
  ADD COLUMN reroute_snapshot_json LONGTEXT NULL,
  ADD COLUMN reroute_input_json LONGTEXT NULL,
  ADD COLUMN reroute_bus_admission_json VARCHAR(1000) NULL,
  ADD UNIQUE KEY uk_auto_journey_parent_session (parent_session_id);

DROP PROCEDURE IF EXISTS assert_automatic_journey_reroute_postconditions;
DELIMITER //
CREATE PROCEDURE assert_automatic_journey_reroute_postconditions()
BEGIN
    IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'automatic_journey_preparations' AND is_nullable = 'YES'
        AND ((column_name = 'parent_session_id' AND data_type = 'varchar'
                AND character_maximum_length = 36)
            OR (column_name = 'source_route_fingerprint' AND data_type = 'varchar'
                AND character_maximum_length = 64)
            OR (column_name IN ('reroute_snapshot_json', 'reroute_input_json') AND data_type = 'longtext')
            OR (column_name = 'reroute_bus_admission_json' AND data_type = 'varchar'
                AND character_maximum_length = 1000))) <> 5
        OR NOT EXISTS (SELECT 1 FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'automatic_journey_preparations'
                AND index_name = 'uk_auto_journey_parent_session' AND non_unique = 0) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Reroute schema verification failed; keep feature disabled';
    END IF;
END//
DELIMITER ;
CALL assert_automatic_journey_reroute_postconditions();
DROP PROCEDURE assert_automatic_journey_reroute_postconditions;
INSERT INTO application_schema_migrations(version, description, applied_at)
VALUES ('2026-11-19-automatic-journey-reroute-v1',
    'Versioned automatic journey reroute route and admission evidence', NOW(6));
