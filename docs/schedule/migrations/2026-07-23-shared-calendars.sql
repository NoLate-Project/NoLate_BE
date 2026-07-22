-- NoLate first-class shared calendar migration (MySQL 8.x)
--
-- This migration is additive. Existing category sharing remains readable until every supported
-- client uses calendar membership. Run after a schema backup and before the application rollout.

CREATE TABLE IF NOT EXISTS schedule_calendars (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Shared calendar primary key',
    owner_member_id BIGINT NOT NULL COMMENT 'Current calendar owner member id',
    legacy_category_id BIGINT NULL COMMENT 'Legacy shared category id used during migration',
    title VARCHAR(80) NOT NULL COMMENT 'Shared calendar title',
    color VARCHAR(32) NOT NULL DEFAULT '#2F80FF' COMMENT 'Shared calendar display color',
    default_content_mode VARCHAR(30) NOT NULL DEFAULT 'SCHEDULE_ONLY' COMMENT 'Default shared content mode',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'Calendar lifecycle status',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_calendars_legacy_category (legacy_category_id),
    INDEX idx_schedule_calendars_owner_status (owner_member_id, status, deleted)
) COMMENT='Shared schedule calendars';

CREATE TABLE IF NOT EXISTS schedule_calendar_members (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Shared calendar membership primary key',
    calendar_id BIGINT NOT NULL COMMENT 'Shared calendar id',
    member_id BIGINT NOT NULL COMMENT 'Member id',
    role VARCHAR(20) NOT NULL COMMENT 'OWNER, EDITOR, or VIEWER',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'Membership lifecycle status',
    route_reminder_enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'Missing route reminder preference',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_schedule_calendar_members_calendar_member (calendar_id, member_id),
    INDEX idx_schedule_calendar_members_member_status (member_id, status, deleted),
    INDEX idx_schedule_calendar_members_calendar_status (calendar_id, status, deleted),
    CONSTRAINT fk_schedule_calendar_members_calendar
        FOREIGN KEY (calendar_id) REFERENCES schedule_calendars (id)
        ON DELETE CASCADE
) COMMENT='Shared calendar memberships';

CREATE TABLE IF NOT EXISTS schedule_route_setup_reminders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    schedule_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    schedule_fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    sent_at DATETIME(6) NULL,
    last_error VARCHAR(500) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    create_dt DATETIME(6) NULL,
    update_dt DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_route_setup_reminders_schedule_member_fingerprint
        (schedule_id, member_id, schedule_fingerprint),
    INDEX idx_route_setup_reminders_dispatch (status, next_attempt_at, id),
    INDEX idx_route_setup_reminders_member (member_id, id),
    CONSTRAINT fk_route_setup_reminders_schedule
        FOREIGN KEY (schedule_id) REFERENCES schedules (id)
        ON DELETE CASCADE
) COMMENT='D-3 per-member route setup reminder outbox';

-- MySQL does not support `ADD COLUMN IF NOT EXISTS` consistently across all deployed 8.x patch
-- versions. Each ALTER is selected through information_schema so rerunning the migration is safe.
SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'schedules' AND column_name = 'calendar_id'
);
SET @ddl = IF(
    @column_exists = 0,
    'ALTER TABLE schedules ADD COLUMN calendar_id BIGINT NULL COMMENT ''Shared calendar id'' AFTER category_id',
    'DO 0'
);
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'schedules' AND column_name = 'schedule_type'
);
SET @ddl = IF(
    @column_exists = 0,
    'ALTER TABLE schedules ADD COLUMN schedule_type VARCHAR(20) NOT NULL DEFAULT ''NORMAL'' COMMENT ''NORMAL or ROUTE schedule'' AFTER calendar_id',
    'DO 0'
);
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'schedules' AND column_name = 'calendar_content_mode_override'
);
SET @ddl = IF(
    @column_exists = 0,
    'ALTER TABLE schedules ADD COLUMN calendar_content_mode_override VARCHAR(30) NULL COMMENT ''Per-schedule shared content override'' AFTER schedule_type',
    'DO 0'
);
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'schedule_shares' AND column_name = 'content_mode'
);
SET @ddl = IF(
    @column_exists = 0,
    'ALTER TABLE schedule_shares ADD COLUMN content_mode VARCHAR(30) NOT NULL DEFAULT ''SCHEDULE_AND_TRAVEL'' COMMENT ''Shared content mode'' AFTER permission',
    'DO 0'
);
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'schedule_share_invitations' AND column_name = 'content_mode'
);
SET @ddl = IF(
    @column_exists = 0,
    'ALTER TABLE schedule_share_invitations ADD COLUMN content_mode VARCHAR(30) NOT NULL DEFAULT ''SCHEDULE_AND_TRAVEL'' COMMENT ''Content mode granted on accept'' AFTER permission',
    'DO 0'
);
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'schedules'
      AND index_name = 'idx_schedules_calendar_deleted_start'
);
SET @ddl = IF(
    @index_exists = 0,
    'ALTER TABLE schedules ADD INDEX idx_schedules_calendar_deleted_start (calendar_id, deleted, start_at)',
    'DO 0'
);
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @fk_exists = (
    SELECT COUNT(*) FROM information_schema.referential_constraints
    WHERE constraint_schema = DATABASE() AND table_name = 'schedules'
      AND constraint_name = 'fk_schedules_calendar'
);
SET @ddl = IF(
    @fk_exists = 0,
    'ALTER TABLE schedules ADD CONSTRAINT fk_schedules_calendar FOREIGN KEY (calendar_id) REFERENCES schedule_calendars (id) ON DELETE SET NULL',
    'DO 0'
);
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

-- A category that has at least one active share becomes one calendar. The unique legacy category
-- key makes the INSERT idempotent and lets old clients continue creating schedules during dual-read.
INSERT INTO schedule_calendars (
    owner_member_id,
    legacy_category_id,
    title,
    color,
    default_content_mode,
    status,
    version,
    created_at,
    updated_at,
    deleted,
    create_dt,
    update_dt
)
SELECT
    c.member_id,
    c.id,
    c.title,
    c.color,
    'SCHEDULE_AND_TRAVEL',
    'ACTIVE',
    0,
    NOW(6),
    NOW(6),
    FALSE,
    NOW(6),
    NOW(6)
FROM schedule_categories c
WHERE c.deleted = FALSE
  AND EXISTS (
      SELECT 1 FROM schedule_category_shares scs
      WHERE scs.category_id = c.id AND scs.status = 'ACTIVE' AND scs.deleted = FALSE
  )
  AND NOT EXISTS (
      SELECT 1 FROM schedule_calendars cal WHERE cal.legacy_category_id = c.id
  );

INSERT INTO schedule_calendar_members (
    calendar_id, member_id, role, status, route_reminder_enabled, version,
    created_at, updated_at, deleted, create_dt, update_dt
)
SELECT
    cal.id, cal.owner_member_id, 'OWNER', 'ACTIVE', TRUE, 0,
    NOW(6), NOW(6), FALSE, NOW(6), NOW(6)
FROM schedule_calendars cal
WHERE cal.legacy_category_id IS NOT NULL
ON DUPLICATE KEY UPDATE
    role = 'OWNER', status = 'ACTIVE', deleted = FALSE, deleted_at = NULL;

INSERT INTO schedule_calendar_members (
    calendar_id, member_id, role, status, route_reminder_enabled, version,
    created_at, updated_at, deleted, create_dt, update_dt
)
SELECT
    cal.id,
    scs.target_member_id,
    IF(scs.permission IN ('EDITOR', 'OWNER'), 'EDITOR', 'VIEWER'),
    'ACTIVE',
    TRUE,
    0,
    NOW(6), NOW(6), FALSE, NOW(6), NOW(6)
FROM schedule_category_shares scs
JOIN schedule_calendars cal ON cal.legacy_category_id = scs.category_id
WHERE scs.status = 'ACTIVE' AND scs.deleted = FALSE
ON DUPLICATE KEY UPDATE
    role = VALUES(role), status = 'ACTIVE', deleted = FALSE, deleted_at = NULL;

UPDATE schedules s
JOIN schedule_calendars cal ON cal.legacy_category_id = s.category_id
SET s.calendar_id = cal.id
WHERE s.calendar_id IS NULL AND s.deleted = FALSE;

UPDATE schedules s
JOIN schedule_routes sr ON sr.schedule_id = s.id
SET s.schedule_type = 'ROUTE'
WHERE s.schedule_type = 'NORMAL'
  AND (
      sr.destination_name IS NOT NULL OR sr.destination_lat IS NOT NULL OR
      sr.route_json IS NOT NULL OR sr.travel_minutes IS NOT NULL
  );

-- Verification queries. Each duplicate query must return no rows.
SELECT calendar_id, member_id, COUNT(*) AS duplicate_count
FROM schedule_calendar_members
GROUP BY calendar_id, member_id
HAVING COUNT(*) > 1;

SELECT id, owner_member_id
FROM schedule_calendars cal
WHERE cal.status = 'ACTIVE' AND cal.deleted = FALSE
  AND (
      SELECT COUNT(*) FROM schedule_calendar_members scm
      WHERE scm.calendar_id = cal.id AND scm.role = 'OWNER'
        AND scm.status = 'ACTIVE' AND scm.deleted = FALSE
  ) <> 1;
