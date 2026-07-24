-- Apply after 2026-07-24-push-deliveries.sql, then immediately apply
-- 2026-07-24-push-delivery-linearization.sql before deploying the three code commits.
-- The original draft added raw token/device unique indexes under the database collation.
-- That is intentionally superseded: opaque identity is migrated only through binary
-- SHA-256 fingerprints in the linearization migration.

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

-- Verification: the query must return no row.
SELECT COUNT(*) AS missing_generation_column
FROM information_schema.columns
WHERE table_schema = @schema_name
  AND table_name = 'schedule_push_job'
  AND column_name = 'notification_generation'
HAVING COUNT(*) = 0;
