-- NoLate shared schedule member travel plan migration (MySQL 8.x)
--
-- Run after taking a schema backup. DDL statements auto-commit in MySQL, so execute this
-- file during a deployment maintenance step before starting the new application version.

CREATE TABLE IF NOT EXISTS schedule_travel_plans (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Personal travel plan primary key',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    schedule_id BIGINT NOT NULL COMMENT 'Shared schedule id',
    member_id BIGINT NOT NULL COMMENT 'Travel plan owner member id',
    travel_minutes INT NULL COMMENT 'Estimated travel minutes',
    depart_at DATETIME(6) NULL COMMENT 'Member-specific departure time',
    travel_mode VARCHAR(20) NULL COMMENT 'Member-specific travel mode',
    origin_name VARCHAR(255) NULL COMMENT 'Personal origin name',
    origin_address VARCHAR(500) NULL COMMENT 'Personal origin address',
    origin_lat DOUBLE NULL COMMENT 'Personal origin latitude',
    origin_lng DOUBLE NULL COMMENT 'Personal origin longitude',
    route_json LONGTEXT NULL COMMENT 'Member-selected route detail JSON',
    notification_enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Member-specific departure notification flag',
    notification_lead_minutes INT NULL COMMENT 'Member-specific monitoring lead minutes',
    notification_interval_minutes INT NULL COMMENT 'Member-specific ETA refresh interval',
    schedule_fingerprint VARCHAR(64) NOT NULL COMMENT 'Schedule time and destination fingerprint at save time',
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_travel_plans_schedule_member (schedule_id, member_id),
    INDEX idx_schedule_travel_plans_schedule (schedule_id),
    INDEX idx_schedule_travel_plans_member (member_id),
    INDEX idx_schedule_travel_plans_member_depart_at (member_id, depart_at),
    CONSTRAINT fk_schedule_travel_plans_schedule
        FOREIGN KEY (schedule_id) REFERENCES schedules (id)
        ON DELETE CASCADE
) COMMENT='Per-member travel plans for shared schedules';

-- The previous schema allowed one push job per schedule. Replace that key with a
-- schedule/member key so every participant can own an independent departure alert.
SET @legacy_push_key_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'schedule_push_job'
      AND index_name = 'uk_schedule_push_job_schedule_id'
);
SET @ddl = IF(
    @legacy_push_key_exists > 0,
    'ALTER TABLE schedule_push_job DROP INDEX uk_schedule_push_job_schedule_id',
    'DO 0'
);
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @member_push_key_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'schedule_push_job'
      AND index_name = 'uk_schedule_push_job_schedule_member'
);
SET @ddl = IF(
    @member_push_key_exists = 0,
    'ALTER TABLE schedule_push_job ADD UNIQUE KEY uk_schedule_push_job_schedule_member (schedule_id, member_id)',
    'DO 0'
);
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

-- Verification: each query must return one row with COUNT(*) = 1.
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name = 'schedule_travel_plans';

SELECT COUNT(*)
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'schedule_push_job'
  AND index_name = 'uk_schedule_push_job_schedule_member';
