-- Manual journey guidance pilot, MySQL 8. Apply before deploying the new entity.
-- Intentionally fails if a partial/applied table exists; inspect instead of using --force.
-- No existing departure jobs or ActivityKit rows are changed/backfilled.
DROP PROCEDURE IF EXISTS assert_journey_guidance_preconditions;
DELIMITER //
CREATE PROCEDURE assert_journey_guidance_preconditions()
BEGIN
    IF (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'application_schema_migrations') <> 1
    THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Journey migration requires the existing schema marker table';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'journey_guidance_sessions')
        OR EXISTS (SELECT 1 FROM application_schema_migrations WHERE version = '2026-10-03-journey-guidance-sessions-v1')
    THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Journey schema is partial or already applied; inspect before retry';
    END IF;
END//
DELIMITER ;
CALL assert_journey_guidance_preconditions();
DROP PROCEDURE assert_journey_guidance_preconditions;

CREATE TABLE journey_guidance_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version BIGINT NOT NULL DEFAULT 0,
    session_id VARCHAR(36) NOT NULL,
    member_id BIGINT NOT NULL,
    schedule_id BIGINT NOT NULL,
    login_session_generation BIGINT NOT NULL,
    device_fingerprint VARCHAR(64) NULL,
    generation BIGINT NOT NULL,
    route_revision BIGINT NOT NULL DEFAULT 1,
    route_fingerprint VARCHAR(64) NOT NULL,
    route_snapshot_json LONGTEXT NOT NULL,
    route_legs_json LONGTEXT NOT NULL,
    sequence BIGINT NOT NULL DEFAULT 0,
    phase VARCHAR(30) NOT NULL,
    active_leg_index INT NOT NULL DEFAULT 0,
    current_stop_index INT NULL,
    remaining_stops INT NULL,
    leg_progress DOUBLE NOT NULL DEFAULT 0,
    next_instruction VARCHAR(120) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NULL,
    end_reason VARCHAR(80) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_journey_session_id (session_id),
    INDEX idx_journey_member_schedule (member_id, schedule_id, generation),
    INDEX idx_journey_terminal_expiry (ended_at, expires_at)
) ENGINE=InnoDB COMMENT='Opt-in manual personal transit journey sessions; raw GPS is not collected';

DROP PROCEDURE IF EXISTS assert_journey_guidance_postconditions;
DELIMITER //
CREATE PROCEDURE assert_journey_guidance_postconditions()
BEGIN
    IF (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'journey_guidance_sessions') <> 24
        OR (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'journey_guidance_sessions'
          AND index_name IN ('PRIMARY', 'uk_journey_session_id', 'idx_journey_member_schedule', 'idx_journey_terminal_expiry')) <> 4
    THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Journey schema verification failed; keep the feature disabled';
    END IF;
END//
DELIMITER ;
CALL assert_journey_guidance_postconditions();
DROP PROCEDURE assert_journey_guidance_postconditions;

INSERT INTO application_schema_migrations(version, description, applied_at)
VALUES ('2026-10-03-journey-guidance-sessions-v1', 'Manual journey session lifecycle and route snapshots', NOW(6));
