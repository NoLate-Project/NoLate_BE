-- Apply after 2026-07-24-push-deliveries.sql and before deploying the follow-up code.
-- MySQL 8.x migration: notification generation + legacy token deduplication/constraints.

SET @schema_name = DATABASE();

-- A reused schedule_push_job row must not reuse a generation-0 logical event key after edits.
SET @has_generation = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = @schema_name
      AND table_name = 'schedule_push_job'
      AND column_name = 'notification_generation'
);
SET @ddl = IF(
    @has_generation = 0,
    'ALTER TABLE schedule_push_job ADD COLUMN notification_generation BIGINT NOT NULL DEFAULT 0 COMMENT ''Notification event generation incremented on schedule changes'' AFTER retry_count',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- PENDING manifest rows are not provider attempts. Attempt timestamps start only when
-- a row is atomically moved to DISPATCHING.
ALTER TABLE push_deliveries
    MODIFY COLUMN first_attempted_at DATETIME(6) NULL,
    MODIFY COLUMN last_attempted_at DATETIME(6) NULL;

-- Keep the newest ownership row for a provider token, then the newest row for a stable
-- member/device pair. Run these before adding unique indexes.
DELETE older
FROM push_device_token older
JOIN push_device_token newer
  ON newer.token = older.token
 AND newer.id > older.id;

DELETE older
FROM push_device_token older
JOIN push_device_token newer
  ON newer.member_id = older.member_id
 AND newer.device_id = older.device_id
 AND newer.device_id IS NOT NULL
 AND newer.id > older.id;

SET @has_token_unique = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'push_device_token'
      AND index_name = 'uk_push_device_token_token'
);
SET @ddl = IF(
    @has_token_unique = 0,
    'ALTER TABLE push_device_token ADD UNIQUE KEY uk_push_device_token_token (token)',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_member_device_unique = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = @schema_name
      AND table_name = 'push_device_token'
      AND index_name = 'uk_push_device_token_member_device'
);
SET @ddl = IF(
    @has_member_device_unique = 0,
    'ALTER TABLE push_device_token ADD UNIQUE KEY uk_push_device_token_member_device (member_id, device_id)',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Verification: all three queries must return zero rows/counts after migration.
SELECT token, COUNT(*) AS duplicate_count
FROM push_device_token
GROUP BY token
HAVING COUNT(*) > 1;

SELECT member_id, device_id, COUNT(*) AS duplicate_count
FROM push_device_token
WHERE device_id IS NOT NULL
GROUP BY member_id, device_id
HAVING COUNT(*) > 1;

SELECT COUNT(*) AS missing_generation_column
FROM information_schema.columns
WHERE table_schema = @schema_name
  AND table_name = 'schedule_push_job'
  AND column_name = 'notification_generation'
HAVING COUNT(*) = 0;
