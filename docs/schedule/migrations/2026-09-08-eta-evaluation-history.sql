-- Append-only ETA evaluation summaries (MySQL 8.x).
-- Apply before deploying the new entity/production guard. DDL commits implicitly.
-- Run with the MySQL CLI without --force so a failed pre/postcondition stops the script.
-- Existing push-job state is not backfilled: it cannot reconstruct past evaluations.
-- IDs are snapshots without foreign keys; retention/deletion policy will be connected
-- with the history writer rather than silently cascading when a push job is replaced.

DROP PROCEDURE IF EXISTS assert_eta_evaluation_history_preconditions;
DELIMITER //
CREATE PROCEDURE assert_eta_evaluation_history_preconditions()
BEGIN
    IF (
        SELECT COUNT(*)
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name IN ('application_schema_migrations', 'schedule_push_job')
    ) <> 2 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'ETA evaluation history migration blocked: required tables are absent';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM application_schema_migrations
        WHERE version = '2026-08-10-effective-transit-route-v1'
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'ETA evaluation history migration blocked: predecessor marker is absent';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_eta_evaluation_history'
    ) OR EXISTS (
        SELECT 1
        FROM application_schema_migrations
        WHERE version = '2026-09-08-eta-evaluation-history-v1'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'ETA evaluation history migration blocked: partial or applied schema requires inspection';
    END IF;
END//
DELIMITER ;

CALL assert_eta_evaluation_history_preconditions();
DROP PROCEDURE assert_eta_evaluation_history_preconditions;

CREATE TABLE schedule_eta_evaluation_history (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'ETA evaluation history primary key',
    evaluation_id VARCHAR(36) NOT NULL COMMENT 'Canonical UUID reused only for the same evaluation save retry',
    push_job_id BIGINT NOT NULL COMMENT 'Evaluated push-job snapshot id without lifecycle FK coupling',
    schedule_id BIGINT NOT NULL COMMENT 'Evaluated schedule snapshot id',
    member_id BIGINT NOT NULL COMMENT 'Evaluated member snapshot id',
    evaluated_at DATETIME(6) NOT NULL COMMENT 'Calculation request reference time',
    eta_source VARCHAR(30) NOT NULL COMMENT 'Provider or fallback result source',
    eta_stale BOOLEAN NOT NULL COMMENT 'Result freshness or low-confidence diagnostic',
    failure_reason VARCHAR(500) NULL COMMENT 'Sanitized failure code or safe diagnostic; null on success',
    travel_minutes INT NOT NULL COMMENT 'Travel duration returned by this evaluation',
    recommended_departure_at DATETIME(6) NULL COMMENT 'Calculated departure; null when unavailable',
    predicted_arrival_at DATETIME(6) NULL COMMENT 'Calculated arrival; may exceed the target',
    target_arrival_at DATETIME(6) NOT NULL COMMENT 'Target arrival frozen at evaluation time',
    provider_fetched_at DATETIME(6) NULL COMMENT 'TrafficResult fetchedAt; null for stored-route fallback',
    PRIMARY KEY (id),
    UNIQUE KEY uk_eta_evaluation_id (evaluation_id),
    INDEX idx_eta_evaluation_schedule_member_time (schedule_id, member_id, evaluated_at, id),
    INDEX idx_eta_evaluation_job_time (push_job_id, evaluated_at, id),
    INDEX idx_eta_evaluation_time (evaluated_at, id)
) ENGINE=InnoDB COMMENT='Append-only successful and fallback ETA evaluation summaries';

DROP PROCEDURE IF EXISTS assert_eta_evaluation_history_postconditions;
DELIMITER //
CREATE PROCEDURE assert_eta_evaluation_history_postconditions()
BEGIN
    IF (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_eta_evaluation_history'
    ) <> 14 OR (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_eta_evaluation_history'
          AND (
              (column_name IN ('id', 'push_job_id', 'schedule_id', 'member_id')
                  AND column_type = 'bigint' AND is_nullable = 'NO')
              OR (column_name = 'travel_minutes' AND column_type = 'int' AND is_nullable = 'NO')
              OR (column_name = 'evaluation_id' AND data_type = 'varchar'
                  AND character_maximum_length = 36 AND is_nullable = 'NO')
              OR (column_name = 'eta_source' AND data_type = 'varchar'
                  AND character_maximum_length = 30 AND is_nullable = 'NO')
              OR (column_name = 'failure_reason' AND data_type = 'varchar'
                  AND character_maximum_length = 500 AND is_nullable = 'YES')
              OR (column_name = 'eta_stale' AND data_type = 'tinyint' AND is_nullable = 'NO')
              OR (column_name IN ('evaluated_at', 'target_arrival_at')
                  AND data_type = 'datetime' AND datetime_precision = 6 AND is_nullable = 'NO')
              OR (column_name IN ('recommended_departure_at', 'predicted_arrival_at', 'provider_fetched_at')
                  AND data_type = 'datetime' AND datetime_precision = 6 AND is_nullable = 'YES')
          )
    ) <> 14 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'ETA evaluation history verification failed: column contract differs';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_eta_evaluation_history'
          AND column_name = 'id'
          AND extra = 'auto_increment'
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'ETA evaluation history verification failed: identity column is absent';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM (
            SELECT index_name, MIN(non_unique) AS non_unique,
                   GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS indexed_columns
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'schedule_eta_evaluation_history'
            GROUP BY index_name
        ) AS index_definitions
        WHERE (index_name = 'PRIMARY' AND non_unique = 0 AND indexed_columns = 'id')
           OR (index_name = 'uk_eta_evaluation_id' AND non_unique = 0 AND indexed_columns = 'evaluation_id')
           OR (index_name = 'idx_eta_evaluation_schedule_member_time' AND non_unique = 1
               AND indexed_columns = 'schedule_id,member_id,evaluated_at,id')
           OR (index_name = 'idx_eta_evaluation_job_time' AND non_unique = 1
               AND indexed_columns = 'push_job_id,evaluated_at,id')
           OR (index_name = 'idx_eta_evaluation_time' AND non_unique = 1
               AND indexed_columns = 'evaluated_at,id')
    ) <> 5 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'ETA evaluation history verification failed: index contract differs';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.referential_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'schedule_eta_evaluation_history'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'ETA evaluation history verification failed: unexpected lifecycle foreign key';
    END IF;
END//
DELIMITER ;

CALL assert_eta_evaluation_history_postconditions();
DROP PROCEDURE assert_eta_evaluation_history_postconditions;

-- Only a verified schema may satisfy ProductionSchemaVersionGuard.
INSERT INTO application_schema_migrations(version, description, applied_at)
VALUES (
    '2026-09-08-eta-evaluation-history-v1',
    'Append-only ETA evaluation summaries with per-evaluation deduplication',
    CURRENT_TIMESTAMP(6)
);

SELECT COUNT(*) AS eta_evaluation_history_marker_count
FROM application_schema_migrations
WHERE version = '2026-09-08-eta-evaluation-history-v1';
