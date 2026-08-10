-- TimeTree-style calendar-scoped categories.
-- NULL calendar_id remains the member's private/personal calendar category.

ALTER TABLE schedule_categories
    ADD COLUMN calendar_id BIGINT NULL
        COMMENT 'Owning shared calendar id; NULL for personal categories'
        AFTER member_id,
    ADD INDEX idx_schedule_categories_calendar_deleted_sort
        (calendar_id, deleted, sort_order),
    ADD CONSTRAINT fk_schedule_categories_calendar
        FOREIGN KEY (calendar_id) REFERENCES schedule_calendars (id);

-- The 2026-07-23 migration created one shared calendar per legacy shared category.
-- Promote those categories into the matching calendar without duplicating data.
UPDATE schedule_categories category
JOIN schedule_calendars calendar
  ON calendar.legacy_category_id = category.id
 AND calendar.deleted = FALSE
SET category.calendar_id = calendar.id
WHERE category.calendar_id IS NULL
  AND category.deleted = FALSE;

-- Calendars created before this release did not create a category. Give every
-- active calendar a usable first category so the mobile form is never empty.
INSERT INTO schedule_categories (
    member_id,
    calendar_id,
    title,
    color,
    sort_order,
    deleted
)
SELECT
    calendar.owner_member_id,
    calendar.id,
    '일정',
    calendar.color,
    0,
    FALSE
FROM schedule_calendars calendar
WHERE calendar.status = 'ACTIVE'
  AND calendar.deleted = FALSE
  AND NOT EXISTS (
      SELECT 1
      FROM schedule_categories category
      WHERE category.calendar_id = calendar.id
        AND category.deleted = FALSE
  );
