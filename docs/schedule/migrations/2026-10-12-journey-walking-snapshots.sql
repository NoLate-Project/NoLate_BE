-- Manual MySQL 8 migration. Stop API/workers, take a backup; never run from an app/test/build.
-- No backfill. Only server-owned planned paths with 300s TTL, no raw GPS/provider body/key/token.
DROP PROCEDURE IF EXISTS assert_walking_snapshot_preconditions;
DELIMITER //
CREATE PROCEDURE assert_walking_snapshot_preconditions()
BEGIN
    IF (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
        AND table_name = 'application_schema_migrations') <> 1
        OR NOT EXISTS (SELECT 1 FROM application_schema_migrations
            WHERE version = '2026-10-06-journey-activity-lanes-v1') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Walking snapshot requires reviewed journey schema';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE()
        AND table_name = 'journey_walking_snapshots') OR EXISTS (SELECT 1 FROM application_schema_migrations
        WHERE version = '2026-10-12-journey-walking-snapshots-v1') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Walking snapshot schema exists or partial; inspect before retry';
    END IF;
END//
DELIMITER ;
CALL assert_walking_snapshot_preconditions();
DROP PROCEDURE assert_walking_snapshot_preconditions;

CREATE TABLE journey_walking_snapshots (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version BIGINT NOT NULL DEFAULT 0,
    member_id BIGINT NOT NULL,
    schedule_id BIGINT NOT NULL,
    snapshot_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    contract_json LONGTEXT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_walking_owner_schedule (member_id, schedule_id),
    UNIQUE KEY uk_walking_snapshot_id (snapshot_id),
    INDEX idx_walking_expiry (expires_at_ms)
) ENGINE=InnoDB COMMENT='Server-owned short-lived planned walking paths; no GPS/admission';

DROP PROCEDURE IF EXISTS assert_walking_snapshot_postconditions;
DELIMITER //
CREATE PROCEDURE assert_walking_snapshot_postconditions()
BEGIN
    IF (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'journey_walking_snapshots') <> 7
        OR (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'journey_walking_snapshots' AND is_nullable = 'NO'
        AND column_name IN ('id','version','member_id','schedule_id','snapshot_id','expires_at_ms','contract_json')) <> 7
        OR (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema = DATABASE()
        AND table_name = 'journey_walking_snapshots' AND index_name IN
        ('PRIMARY','uk_walking_owner_schedule','uk_walking_snapshot_id','idx_walking_expiry')) <> 4 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Walking snapshot postconditions failed; keep API/workers stopped';
    END IF;
END//
DELIMITER ;
CALL assert_walking_snapshot_postconditions();
DROP PROCEDURE assert_walking_snapshot_postconditions;
INSERT INTO application_schema_migrations(version, description, applied_at)
VALUES ('2026-10-12-journey-walking-snapshots-v1', 'Owned walking paths with TTL, replacement and cancellation', NOW(6));
-- On deploy: runtime OFF first, validate schema + cleanup wiring, then separately approve enablement.
-- Roll back application only while API/workers are stopped. Do not drop this table/marker blindly.
-- If a partial migration exists, inspect it manually; never rerun past the precondition guard.
