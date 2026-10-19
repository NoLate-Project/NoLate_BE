-- MySQL 8 manual migration. Do not run against an existing database as part of tests/builds.
-- Preparation rows are separate from active manual journeys. No existing rows are backfilled.
DROP PROCEDURE IF EXISTS assert_automatic_journey_preconditions;
DELIMITER //
CREATE PROCEDURE assert_automatic_journey_preconditions()
BEGIN
    IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
        AND table_name = 'application_schema_migrations') <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Automatic journey migration requires schema marker table';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE()
        AND table_name = 'automatic_journey_preparations')
        OR EXISTS (SELECT 1 FROM application_schema_migrations
        WHERE version = '2026-10-04-automatic-journey-preparations-v1') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Automatic journey schema exists or is partial; inspect before retry';
    END IF;
END//
DELIMITER ;
CALL assert_automatic_journey_preconditions();
DROP PROCEDURE assert_automatic_journey_preconditions;

CREATE TABLE automatic_journey_preparations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version BIGINT NOT NULL DEFAULT 0,
    session_id VARCHAR(36) NOT NULL,
    member_id BIGINT NOT NULL,
    schedule_id BIGINT NOT NULL,
    login_session_generation BIGINT NOT NULL,
    client_owner_id VARCHAR(36) NOT NULL,
    route_input_digest VARCHAR(64) NOT NULL,
    route_fingerprint VARCHAR(64) NOT NULL,
    generation BIGINT NOT NULL,
    route_revision BIGINT NOT NULL DEFAULT 1,
    execution VARCHAR(12) NOT NULL,
    state VARCHAR(12) NOT NULL,
    sequence BIGINT NOT NULL DEFAULT 0,
    prepared_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    event_key VARCHAR(120) NULL,
    occurred_at DATETIME(6) NULL,
    departed_at DATETIME(6) NULL,
    ended_at DATETIME(6) NULL,
    retention_expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_auto_journey_session (session_id),
    UNIQUE KEY uk_auto_journey_generation (member_id, schedule_id, generation),
    INDEX idx_auto_journey_expiry (state, expires_at),
    INDEX idx_auto_journey_retention (retention_expires_at)
) ENGINE=InnoDB COMMENT='Automatic departure preparation/receipt, no location trace or route snapshot';

DROP PROCEDURE IF EXISTS assert_automatic_journey_postconditions;
DELIMITER //
CREATE PROCEDURE assert_automatic_journey_postconditions()
BEGIN
    IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'automatic_journey_preparations') <> 21
        OR (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'automatic_journey_preparations'
        AND index_name IN ('PRIMARY', 'uk_auto_journey_session', 'uk_auto_journey_generation',
        'idx_auto_journey_expiry', 'idx_auto_journey_retention')) <> 5 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Automatic journey schema verification failed; keep feature disabled';
    END IF;
END//
DELIMITER ;
CALL assert_automatic_journey_postconditions();
DROP PROCEDURE assert_automatic_journey_postconditions;
INSERT INTO application_schema_migrations(version, description, applied_at)
VALUES ('2026-10-04-automatic-journey-preparations-v1', 'Isolated automatic departure preparation and durable event receipt', NOW(6));
