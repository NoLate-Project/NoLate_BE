-- Durable schedule calendar cache generation rows.
--
-- Atomic-cutover requirements:
--   1) Stop and drain every old API and worker instance. Mixed old Redis-revision writers and
--      new DB-revision readers are forbidden because an old mutation cannot bump this table.
--   2) Confirm all required predecessor markers below exist exactly once.
--   3) Apply this file and complete the member backfill while every application remains stopped.
--   4) Deploy only the new version that reads DB generations and the Redis `v2` scoped namespace,
--      then start it after both postcondition queries return 0 and marker/table queries return 1.
--
-- MySQL DDL commits implicitly. The application refuses production startup until the marker
-- at the bottom exists, and the marker is inserted only after schema/backfill postconditions pass.

DROP PROCEDURE IF EXISTS assert_schedule_cache_revision_preconditions;
DELIMITER //
CREATE PROCEDURE assert_schedule_cache_revision_preconditions()
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'member'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'schedule cache revision migration blocked: member table is absent';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'application_schema_migrations'
    ) OR (
        SELECT COUNT(*)
        FROM application_schema_migrations
        WHERE version IN (
            '2026-07-24-push-reliability-v4',
            '2026-07-26-apple-token-lifecycle-v1',
            '2026-07-26-account-deletion-v1'
        )
    ) <> 3 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'schedule cache revision migration blocked: required predecessor marker is absent';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_calendar_cache_revisions'
    ) OR EXISTS (
        SELECT 1
        FROM application_schema_migrations
        WHERE version = '2026-07-27-schedule-calendar-cache-revision-v1'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'schedule cache revision migration blocked: partial or already-applied schema requires inspection';
    END IF;
END//
DELIMITER ;

CALL assert_schedule_cache_revision_preconditions();
DROP PROCEDURE assert_schedule_cache_revision_preconditions;

CREATE TABLE schedule_calendar_cache_revisions (
    member_id BIGINT NOT NULL COMMENT 'Member-scoped cache authority without a member FK',
    revision BIGINT NOT NULL DEFAULT 0 COMMENT 'Durable monthly schedule cache generation',
    PRIMARY KEY (member_id),
    CONSTRAINT chk_schedule_calendar_cache_revision_nonnegative CHECK (revision >= 0)
) COMMENT='Independent lock rows for durable schedule calendar cache generations';

-- Old and new writers are stopped, so this is a complete point-in-time backfill. New signup
-- transactions insert their revision row together with the member after this application starts.
INSERT INTO schedule_calendar_cache_revisions(member_id, revision)
SELECT id, 0
FROM `member`
ORDER BY id;

DROP PROCEDURE IF EXISTS assert_schedule_cache_revision_postconditions;
DELIMITER //
CREATE PROCEDURE assert_schedule_cache_revision_postconditions()
BEGIN
    IF (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_calendar_cache_revisions'
          AND (
              (column_name = 'member_id' AND data_type = 'bigint' AND is_nullable = 'NO')
              OR (
                  column_name = 'revision'
                  AND data_type = 'bigint'
                  AND is_nullable = 'NO'
                  AND column_default = '0'
              )
          )
    ) <> 2 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'schedule cache revision migration verification failed: table contract is absent';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM `member` member_row
        LEFT JOIN schedule_calendar_cache_revisions cache_revision
          ON cache_revision.member_id = member_row.id
        WHERE cache_revision.member_id IS NULL
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'schedule cache revision migration verification failed: member backfill is incomplete';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM schedule_calendar_cache_revisions
        WHERE revision < 0
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'schedule cache revision migration verification failed: invalid generation value';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.referential_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'schedule_calendar_cache_revisions'
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT =
                'schedule cache revision migration verification failed: revision table must not have a foreign key';
    END IF;
END//
DELIMITER ;

CALL assert_schedule_cache_revision_postconditions();
DROP PROCEDURE assert_schedule_cache_revision_postconditions;

INSERT INTO application_schema_migrations(version, description, applied_at)
VALUES (
    '2026-07-27-schedule-calendar-cache-revision-v1',
    'Independent durable schedule calendar cache generation rows',
    CURRENT_TIMESTAMP(6)
);

-- Human-readable verification: marker/table counts must be 1; missing/invalid/FK counts must be 0.
SELECT COUNT(*) AS schedule_cache_revision_marker_count
FROM application_schema_migrations
WHERE version = '2026-07-27-schedule-calendar-cache-revision-v1';

SELECT COUNT(*) AS schedule_cache_revision_table_count
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name = 'schedule_calendar_cache_revisions';

SELECT COUNT(*) AS missing_schedule_cache_revision_rows
FROM `member` member_row
LEFT JOIN schedule_calendar_cache_revisions cache_revision
  ON cache_revision.member_id = member_row.id
WHERE cache_revision.member_id IS NULL;

SELECT COUNT(*) AS invalid_schedule_cache_revision_rows
FROM schedule_calendar_cache_revisions
WHERE revision < 0;

SELECT COUNT(*) AS unexpected_schedule_cache_revision_foreign_keys
FROM information_schema.referential_constraints
WHERE constraint_schema = DATABASE()
  AND table_name = 'schedule_calendar_cache_revisions';
