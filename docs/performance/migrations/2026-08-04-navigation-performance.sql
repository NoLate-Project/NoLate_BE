-- Identifier-free navigation performance telemetry (MySQL 8.x).
--
-- Stop all API instances before applying this migration. The application uses
-- Hibernate validate in production and will not start until this exact table and
-- the final marker both exist.

DROP PROCEDURE IF EXISTS assert_navigation_performance_preconditions;
DELIMITER //
CREATE PROCEDURE assert_navigation_performance_preconditions()
BEGIN
    IF (
        SELECT COUNT(*)
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'application_schema_migrations'
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'navigation performance migration blocked: migration marker table is absent';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM application_schema_migrations
        WHERE version = '2026-08-04-navigation-performance-v1'
          AND description <> 'Identifier-free navigation performance telemetry'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'navigation performance migration blocked: marker description is incompatible';
    END IF;
END//
DELIMITER ;

CALL assert_navigation_performance_preconditions();
DROP PROCEDURE assert_navigation_performance_preconditions;

CREATE TABLE IF NOT EXISTS navigation_performance_events (
    event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'Client-generated UUID for idempotent batch retry',
    member_id BIGINT NOT NULL COMMENT 'Owner used for authorization and account cleanup',
    from_route VARCHAR(80) NOT NULL COMMENT 'Identifier-free normalized source route template',
    to_route VARCHAR(80) NOT NULL COMMENT 'Identifier-free normalized destination route template',
    navigation_action VARCHAR(30) NOT NULL COMMENT 'Bounded React Navigation action',
    route_ready_ms INT NOT NULL COMMENT 'Dispatch to destination route render in milliseconds',
    total_ms INT NOT NULL COMMENT 'Dispatch to transition completion in milliseconds',
    completion_kind VARCHAR(24) NOT NULL COMMENT 'TRANSITION, FRAME, or NEXT_NAVIGATION',
    client_platform VARCHAR(16) NOT NULL COMMENT 'IOS, ANDROID, or WEB',
    app_version VARCHAR(32) NULL,
    build_version VARCHAR(32) NULL,
    occurred_at DATETIME(6) NOT NULL COMMENT 'Client-observed navigation start',
    received_at DATETIME(6) NOT NULL COMMENT 'Server batch receipt time',
    expires_at DATETIME(6) NOT NULL COMMENT 'Automatic 90-day retention boundary',
    PRIMARY KEY (event_id),
    INDEX idx_nav_perf_screen (to_route, occurred_at),
    INDEX idx_nav_perf_slow (total_ms, occurred_at),
    INDEX idx_nav_perf_member_expiry (member_id, expires_at),
    CONSTRAINT chk_nav_perf_route_ready CHECK (route_ready_ms BETWEEN 0 AND 120000),
    CONSTRAINT chk_nav_perf_total CHECK (total_ms BETWEEN route_ready_ms AND 120000),
    CONSTRAINT chk_nav_perf_completion CHECK (
        completion_kind IN ('TRANSITION', 'FRAME', 'NEXT_NAVIGATION')
    ),
    CONSTRAINT chk_nav_perf_platform CHECK (client_platform IN ('IOS', 'ANDROID', 'WEB'))
) COMMENT='Identifier-free 90-day authenticated screen navigation performance telemetry';

DROP PROCEDURE IF EXISTS assert_navigation_performance_postconditions;
DELIMITER //
CREATE PROCEDURE assert_navigation_performance_postconditions()
BEGIN
    IF (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'navigation_performance_events'
          AND column_name IN (
              'event_id', 'member_id', 'from_route', 'to_route', 'navigation_action',
              'route_ready_ms', 'total_ms', 'completion_kind', 'client_platform',
              'app_version', 'build_version', 'occurred_at', 'received_at', 'expires_at'
          )
    ) <> 14 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'navigation performance migration verification failed: column contract is absent';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'navigation_performance_events'
          AND index_name IN (
              'PRIMARY', 'idx_nav_perf_screen', 'idx_nav_perf_slow',
              'idx_nav_perf_member_expiry'
          )
    ) <> 7 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'navigation performance migration verification failed: index contract is absent';
    END IF;
END//
DELIMITER ;

CALL assert_navigation_performance_postconditions();
DROP PROCEDURE assert_navigation_performance_postconditions;

INSERT INTO application_schema_migrations(version, description, applied_at)
SELECT
    '2026-08-04-navigation-performance-v1',
    'Identifier-free navigation performance telemetry',
    CURRENT_TIMESTAMP(6)
WHERE NOT EXISTS (
    SELECT 1
    FROM application_schema_migrations
    WHERE version = '2026-08-04-navigation-performance-v1'
);

SELECT COUNT(*) AS navigation_performance_marker_count
FROM application_schema_migrations
WHERE version = '2026-08-04-navigation-performance-v1';

SELECT
    to_route,
    COUNT(*) AS sample_count,
    ROUND(AVG(route_ready_ms)) AS avg_route_ready_ms,
    ROUND(AVG(total_ms)) AS avg_total_ms,
    MAX(total_ms) AS max_total_ms
FROM navigation_performance_events
WHERE occurred_at >= CURRENT_TIMESTAMP - INTERVAL 7 DAY
GROUP BY to_route
ORDER BY avg_total_ms DESC;
