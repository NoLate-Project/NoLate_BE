-- MySQL 8 manual migration. Keep API/workers stopped; never auto-apply to an existing DB from tests/builds.
-- The isolated disposable MySQL verification gate may apply this file to its own empty database.
-- No existing departure widget token/content is transferred or backfilled.
DROP PROCEDURE IF EXISTS assert_journey_activity_preconditions;
DELIMITER //
CREATE PROCEDURE assert_journey_activity_preconditions()
BEGIN
    IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
        AND table_name = 'application_schema_migrations') <> 1
        OR NOT EXISTS (SELECT 1 FROM application_schema_migrations
        WHERE version = '2026-10-04-automatic-journey-preparations-v1') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Journey activity requires reviewed automatic journey schema';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE()
        AND table_name = 'journey_activity_lanes')
        OR EXISTS (SELECT 1 FROM application_schema_migrations
        WHERE version = '2026-10-06-journey-activity-lanes-v1') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Journey activity schema exists or is partial; inspect before retry';
    END IF;
END//
DELIMITER ;
CALL assert_journey_activity_preconditions();
DROP PROCEDURE assert_journey_activity_preconditions;

CREATE TABLE journey_activity_lanes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version BIGINT NOT NULL DEFAULT 0,
    session_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    member_id BIGINT NOT NULL,
    schedule_id BIGINT NOT NULL,
    login_generation BIGINT NOT NULL,
    client_owner_id VARCHAR(36) NOT NULL,
    route_input_digest VARCHAR(64) NOT NULL,
    generation BIGINT NOT NULL,
    route_revision BIGINT NOT NULL,
    activity_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    token_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fcm_fingerprint VARCHAR(64) NOT NULL,
    push_token_id BIGINT NOT NULL,
    fcm_ownership_version BIGINT NOT NULL,
    device_fingerprint VARCHAR(64) NOT NULL,
    encrypted_credentials VARCHAR(12000) NOT NULL,
    last_timestamp BIGINT NOT NULL,
    content_json TEXT NOT NULL,
    end_state VARCHAR(16) NOT NULL,
    terminal_reason VARCHAR(12) NULL,
    terminal_at DATETIME(6) NULL,
    claimed_at DATETIME(6) NULL,
    failure_code VARCHAR(48) NOT NULL,
    retention_expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_journey_activity_session (session_id),
    UNIQUE KEY uk_journey_activity_token (token_fingerprint),
    UNIQUE KEY uk_journey_activity_id (activity_id),
    INDEX idx_journey_activity_terminal (end_state, id),
    INDEX idx_journey_activity_retention (retention_expires_at)
) ENGINE=InnoDB COMMENT='Journey-only encrypted Activity credentials and durable END intent; no raw GPS';

DROP PROCEDURE IF EXISTS assert_journey_activity_postconditions;
DELIMITER //
CREATE PROCEDURE assert_journey_activity_postconditions()
BEGIN
    IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'journey_activity_lanes') <> 25
        OR (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'journey_activity_lanes'
        AND index_name IN ('PRIMARY', 'uk_journey_activity_session', 'uk_journey_activity_token',
            'uk_journey_activity_id', 'idx_journey_activity_terminal', 'idx_journey_activity_retention')) <> 6 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Journey activity postconditions failed; keep feature disabled';
    END IF;
END//
DELIMITER ;
CALL assert_journey_activity_postconditions();
DROP PROCEDURE assert_journey_activity_postconditions;
INSERT INTO application_schema_migrations(version, description, applied_at)
VALUES ('2026-10-06-journey-activity-lanes-v1', 'Journey encrypted credentials and durable END reservation', NOW(6));
