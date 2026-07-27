-- Schedule push ETA provenance schema upgrade.
--
-- Each column is guarded independently so this file can repair a deployment where an earlier
-- multi-column ALTER stopped after creating only some columns. Run against the intended schema;
-- DATABASE() is used deliberately so a similarly named table in another schema is never changed.

SET @eta_column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'schedule_push_job'
      AND column_name = 'last_live_fetched_at'
);
SET @eta_ddl := IF(
    @eta_column_exists = 0,
    'ALTER TABLE schedule_push_job ADD COLUMN last_live_fetched_at DATETIME(6) NULL COMMENT ''Last successful live provider fetch time'' AFTER last_checked_at',
    'SELECT ''last_live_fetched_at already exists'' AS eta_migration_status'
);
PREPARE eta_stmt FROM @eta_ddl;
EXECUTE eta_stmt;
DEALLOCATE PREPARE eta_stmt;

SET @eta_column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'schedule_push_job'
      AND column_name = 'last_live_travel_minutes'
);
SET @eta_ddl := IF(
    @eta_column_exists = 0,
    'ALTER TABLE schedule_push_job ADD COLUMN last_live_travel_minutes INT NULL COMMENT ''Last trusted live provider travel minutes'' AFTER last_live_fetched_at',
    'SELECT ''last_live_travel_minutes already exists'' AS eta_migration_status'
);
PREPARE eta_stmt FROM @eta_ddl;
EXECUTE eta_stmt;
DEALLOCATE PREPARE eta_stmt;

SET @eta_column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'schedule_push_job'
      AND column_name = 'last_eta_source'
);
SET @eta_ddl := IF(
    @eta_column_exists = 0,
    'ALTER TABLE schedule_push_job ADD COLUMN last_eta_source VARCHAR(30) NULL COMMENT ''LIVE_PROVIDER, SELECTED_ROUTE, or SAVED_FALLBACK'' AFTER last_live_travel_minutes',
    'SELECT ''last_eta_source already exists'' AS eta_migration_status'
);
PREPARE eta_stmt FROM @eta_ddl;
EXECUTE eta_stmt;
DEALLOCATE PREPARE eta_stmt;

SET @eta_column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'schedule_push_job'
      AND column_name = 'last_eta_stale'
);
SET @eta_ddl := IF(
    @eta_column_exists = 0,
    'ALTER TABLE schedule_push_job ADD COLUMN last_eta_stale BOOLEAN NULL COMMENT ''Whether the last ETA used a stale snapshot'' AFTER last_eta_source',
    'SELECT ''last_eta_stale already exists'' AS eta_migration_status'
);
PREPARE eta_stmt FROM @eta_ddl;
EXECUTE eta_stmt;
DEALLOCATE PREPARE eta_stmt;

SET @eta_column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'schedule_push_job'
      AND column_name = 'last_eta_failure_reason'
);
SET @eta_ddl := IF(
    @eta_column_exists = 0,
    'ALTER TABLE schedule_push_job ADD COLUMN last_eta_failure_reason VARCHAR(500) NULL COMMENT ''Stable fallback or provider failure code and safe message'' AFTER last_eta_stale',
    'SELECT ''last_eta_failure_reason already exists'' AS eta_migration_status'
);
PREPARE eta_stmt FROM @eta_ddl;
EXECUTE eta_stmt;
DEALLOCATE PREPARE eta_stmt;

SET @eta_column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'schedule_push_job'
      AND column_name = 'last_eta_route_fingerprint'
);
SET @eta_ddl := IF(
    @eta_column_exists = 0,
    'ALTER TABLE schedule_push_job ADD COLUMN last_eta_route_fingerprint VARCHAR(64) NULL COMMENT ''Route fingerprint used by the latest ETA snapshot'' AFTER last_eta_failure_reason',
    'SELECT ''last_eta_route_fingerprint already exists'' AS eta_migration_status'
);
PREPARE eta_stmt FROM @eta_ddl;
EXECUTE eta_stmt;
DEALLOCATE PREPARE eta_stmt;

SET @eta_column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'schedule_push_job'
      AND column_name = 'last_traffic_change_minutes'
);
SET @eta_ddl := IF(
    @eta_column_exists = 0,
    'ALTER TABLE schedule_push_job ADD COLUMN last_traffic_change_minutes INT NULL COMMENT ''Last comparable live-to-live ETA delta in minutes'' AFTER last_eta_route_fingerprint',
    'SELECT ''last_traffic_change_minutes already exists'' AS eta_migration_status'
);
PREPARE eta_stmt FROM @eta_ddl;
EXECUTE eta_stmt;
DEALLOCATE PREPARE eta_stmt;

SET @eta_column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'schedule_push_job'
      AND column_name = 'last_changed_at'
);
SET @eta_ddl := IF(
    @eta_column_exists = 0,
    'ALTER TABLE schedule_push_job ADD COLUMN last_changed_at DATETIME(6) NULL COMMENT ''Time of the last comparable live-to-live ETA change'' AFTER last_traffic_change_minutes',
    'SELECT ''last_changed_at already exists'' AS eta_migration_status'
);
PREPARE eta_stmt FROM @eta_ddl;
EXECUTE eta_stmt;
DEALLOCATE PREPARE eta_stmt;

-- Upgrade verification: expected_count must be 8 and every row must have the expected type.
SELECT
    column_name,
    column_type,
    is_nullable,
    ordinal_position
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'schedule_push_job'
  AND column_name IN (
      'last_live_fetched_at',
      'last_live_travel_minutes',
      'last_eta_source',
      'last_eta_stale',
      'last_eta_failure_reason',
      'last_eta_route_fingerprint',
      'last_traffic_change_minutes',
      'last_changed_at'
  )
ORDER BY ordinal_position;

SELECT COUNT(*) AS expected_count
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'schedule_push_job'
  AND column_name IN (
      'last_live_fetched_at',
      'last_live_travel_minutes',
      'last_eta_source',
      'last_eta_stale',
      'last_eta_failure_reason',
      'last_eta_route_fingerprint',
      'last_traffic_change_minutes',
      'last_changed_at'
  );
