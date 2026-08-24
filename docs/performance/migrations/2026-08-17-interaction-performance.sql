-- Identifier-free content and interaction performance telemetry (MySQL 8.x).
--
-- Stop all API instances before applying this migration. Production startup
-- requires the table contract and final marker to exist together.

DROP PROCEDURE IF EXISTS assert_interaction_performance_preconditions;
DELIMITER //
CREATE PROCEDURE assert_interaction_performance_preconditions()
BEGIN
    IF (
        SELECT COUNT(*)
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'application_schema_migrations'
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'interaction performance migration blocked: migration marker table is absent';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM application_schema_migrations
        WHERE version = '2026-08-17-interaction-performance-v1'
          AND description <> 'Identifier-free content and interaction performance telemetry'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'interaction performance migration blocked: marker description is incompatible';
    END IF;
END//
DELIMITER ;

CALL assert_interaction_performance_preconditions();
DROP PROCEDURE assert_interaction_performance_preconditions;

CREATE TABLE IF NOT EXISTS interaction_performance_events (
    event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'Client-generated UUID for idempotent batch retry',
    member_id BIGINT NOT NULL COMMENT 'Owner used for authorization and account cleanup',
    route VARCHAR(80) NOT NULL COMMENT 'Identifier-free normalized route template',
    operation_name VARCHAR(80) NOT NULL COMMENT 'Stable content-ready, interaction, or network operation name',
    performance_kind VARCHAR(24) NOT NULL COMMENT 'CONTENT_READY, INTERACTION, or NETWORK',
    outcome VARCHAR(16) NOT NULL COMMENT 'SUCCESS, ERROR, or CANCELLED',
    duration_ms INT NOT NULL COMMENT 'Operation duration in milliseconds',
    client_platform VARCHAR(16) NOT NULL COMMENT 'IOS, ANDROID, or WEB',
    app_version VARCHAR(32) NULL,
    build_version VARCHAR(32) NULL,
    occurred_at DATETIME(6) NOT NULL COMMENT 'Client-observed operation start',
    received_at DATETIME(6) NOT NULL COMMENT 'Server batch receipt time',
    expires_at DATETIME(6) NOT NULL COMMENT 'Automatic 90-day retention boundary',
    PRIMARY KEY (event_id),
    INDEX idx_interaction_perf_operation (operation_name, occurred_at),
    INDEX idx_interaction_perf_route (route, occurred_at),
    INDEX idx_interaction_perf_slow (duration_ms, occurred_at),
    INDEX idx_interaction_perf_member_expiry (member_id, expires_at),
    CONSTRAINT chk_interaction_perf_duration CHECK (duration_ms BETWEEN 0 AND 120000),
    CONSTRAINT chk_interaction_perf_operation CHECK (
        operation_name IN (
            'schedule.content_ready', 'schedule.range_load', 'schedule.range_refresh',
            'schedule.revision_sync', 'schedule.calendar_metadata_load',
            'schedule.detail_load', 'schedule.detail_content_ready',
            'calendar.settings_content_ready', 'calendar.list_load',
            'calendar.members_load', 'category.settings_content_ready',
            'category.list_load', 'quick_schedule.analyze', 'route.search',
            'alarm.content_ready', 'alarm.preview_schedule_load',
            'alarm.complete_departure'
        )
    ),
    CONSTRAINT chk_interaction_perf_kind CHECK (
        performance_kind IN ('CONTENT_READY', 'INTERACTION', 'NETWORK')
    ),
    CONSTRAINT chk_interaction_perf_outcome CHECK (
        outcome IN ('SUCCESS', 'ERROR', 'CANCELLED')
    ),
    CONSTRAINT chk_interaction_perf_platform CHECK (
        client_platform IN ('IOS', 'ANDROID', 'WEB')
    )
) COMMENT='Identifier-free 90-day authenticated content and interaction performance telemetry';

DROP PROCEDURE IF EXISTS assert_interaction_performance_postconditions;
DELIMITER //
CREATE PROCEDURE assert_interaction_performance_postconditions()
BEGIN
    IF (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'interaction_performance_events'
          AND column_name IN (
              'event_id', 'member_id', 'route', 'operation_name', 'performance_kind',
              'outcome', 'duration_ms', 'client_platform', 'app_version',
              'build_version', 'occurred_at', 'received_at', 'expires_at'
          )
    ) <> 13 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'interaction performance migration verification failed: column contract is absent';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'interaction_performance_events'
          AND index_name IN (
              'PRIMARY', 'idx_interaction_perf_operation', 'idx_interaction_perf_route',
              'idx_interaction_perf_slow', 'idx_interaction_perf_member_expiry'
          )
    ) <> 9 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'interaction performance migration verification failed: index contract is absent';
    END IF;
END//
DELIMITER ;

CALL assert_interaction_performance_postconditions();
DROP PROCEDURE assert_interaction_performance_postconditions;

INSERT INTO application_schema_migrations(version, description, applied_at)
SELECT
    '2026-08-17-interaction-performance-v1',
    'Identifier-free content and interaction performance telemetry',
    CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (
    SELECT 1
    FROM application_schema_migrations
    WHERE version = '2026-08-17-interaction-performance-v1'
);

SELECT COUNT(*) AS interaction_performance_marker_count
FROM application_schema_migrations
WHERE version = '2026-08-17-interaction-performance-v1';

SELECT
    route,
    operation_name,
    performance_kind,
    outcome,
    COUNT(*) AS sample_count,
    ROUND(AVG(duration_ms)) AS avg_duration_ms,
    MAX(duration_ms) AS max_duration_ms,
    SUM(duration_ms > 200) AS over_200ms_count,
    ROUND(100 * SUM(duration_ms <= 200) / COUNT(*), 1) AS within_200ms_percent
FROM interaction_performance_events
WHERE occurred_at >= UTC_TIMESTAMP - INTERVAL 7 DAY
GROUP BY route, operation_name, performance_kind, outcome
ORDER BY avg_duration_ms DESC;
