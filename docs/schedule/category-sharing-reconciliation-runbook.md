# Category Sharing Reconciliation Runbook

Status: read-only preflight; destructive reconciliation is intentionally not included

Last updated: 2026-09-09 KST

This runbook audits production data before enabling first-class category sharing for categories
inside shared calendars. Run the queries against a snapshot or read replica first. They return ids
and counts only; do not export invitation token hashes, emails, route JSON, locations, or notes.

The historical migrations must not be rerun blindly:

- `2026-07-23-shared-calendars.sql` copied every active category recipient into a generated calendar
  membership.
- `2026-08-10-calendar-scoped-categories.sql` then attached the same category to that calendar.
- Rerunning the first migration can also overwrite a member role with the legacy category role.

Under `schedule-sharing-policy-v1.md`, category grants and calendar memberships are independent.
The copied membership is therefore not a harmless duplicate: it may keep access alive after category
revocation or expose later categories in the generated calendar.

## 1. Preflight counts

### Duplicate legacy grant paths

```sql
SELECT
    COUNT(*) AS duplicate_legacy_grant_paths
FROM schedule_category_shares scs
JOIN schedule_calendars cal
  ON cal.legacy_category_id = scs.category_id
 AND cal.status = 'ACTIVE'
 AND cal.deleted = FALSE
JOIN schedule_calendar_members scm
  ON scm.calendar_id = cal.id
 AND scm.member_id = scs.target_member_id
 AND scm.status = 'ACTIVE'
 AND scm.deleted = FALSE
WHERE scs.status = 'ACTIVE'
  AND scs.deleted = FALSE;
```

### Generated calendars that appear to have been adopted

An automatic decision must not be made from this query alone. Any non-zero signal sends the calendar
to manual review.

```sql
SELECT
    cal.id AS calendar_id,
    cal.legacy_category_id,
    (
        SELECT COUNT(*)
        FROM schedule_categories category
        WHERE category.calendar_id = cal.id
          AND category.id <> cal.legacy_category_id
          AND category.deleted = FALSE
    ) AS extra_category_count,
    (
        SELECT COUNT(*)
        FROM schedule_calendar_members scm
        WHERE scm.calendar_id = cal.id
          AND scm.member_id <> cal.owner_member_id
          AND scm.status = 'ACTIVE'
          AND scm.deleted = FALSE
          AND NOT EXISTS (
              SELECT 1
              FROM schedule_category_shares scs
              WHERE scs.category_id = cal.legacy_category_id
                AND scs.target_member_id = scm.member_id
                AND scs.status = 'ACTIVE'
                AND scs.deleted = FALSE
          )
    ) AS independently_added_member_count,
    (
        SELECT COUNT(*)
        FROM schedule_share_invitations invitation
        WHERE invitation.resource_type = 'CALENDAR'
          AND invitation.resource_id = cal.id
          AND invitation.deleted = FALSE
    ) AS calendar_invitation_count
FROM schedule_calendars cal
WHERE cal.legacy_category_id IS NOT NULL
  AND cal.deleted = FALSE;
```

### Calendar/category scope mismatch

```sql
SELECT COUNT(*) AS schedule_category_scope_mismatch
FROM schedules schedule_row
JOIN schedule_categories category
  ON category.id = schedule_row.category_id
 AND category.deleted = FALSE
WHERE schedule_row.deleted = FALSE
  AND NOT (schedule_row.calendar_id <=> category.calendar_id);
```

This count must be zero before and after the rollout.

### Pending category links with no live resource

```sql
SELECT COUNT(*) AS pending_category_link_without_live_category
FROM schedule_share_invitations invitation
LEFT JOIN schedule_categories category
  ON category.id = invitation.resource_id
 AND category.deleted = FALSE
WHERE invitation.resource_type = 'CATEGORY'
  AND invitation.status = 'PENDING'
  AND invitation.deleted = FALSE
  AND category.id IS NULL;
```

### Category links that now point into a shared calendar

These links are valid in policy v1. A non-zero count proves the runtime must not reject a category
merely because `calendar_id` is present.

```sql
SELECT COUNT(*) AS pending_calendar_bound_category_links
FROM schedule_share_invitations invitation
JOIN schedule_categories category
  ON category.id = invitation.resource_id
 AND category.deleted = FALSE
WHERE invitation.resource_type = 'CATEGORY'
  AND invitation.status = 'PENDING'
  AND invitation.deleted = FALSE
  AND category.calendar_id IS NOT NULL;
```

### Legacy permission inventory

```sql
SELECT 'SCHEDULE' AS resource_type, permission, COUNT(*) AS grant_count
FROM schedule_shares
WHERE status = 'ACTIVE' AND deleted = FALSE
GROUP BY permission
UNION ALL
SELECT 'CATEGORY', permission, COUNT(*)
FROM schedule_category_shares
WHERE status = 'ACTIVE' AND deleted = FALSE
GROUP BY permission
UNION ALL
SELECT 'INVITATION', permission, COUNT(*)
FROM schedule_share_invitations
WHERE status = 'PENDING' AND deleted = FALSE
GROUP BY permission;
```

`COMMENTER` rows remain readable as `VIEWER`; do not rewrite them until all supported server and
client versions normalize the value the same way.

### Editor-authored category content

This inventory is an implementation gate for author-versus-owner semantics.

```sql
SELECT COUNT(*) AS schedules_authored_outside_category_owner
FROM schedules schedule_row
JOIN schedule_categories category
  ON category.id = schedule_row.category_id
 AND category.deleted = FALSE
LEFT JOIN schedule_calendars calendar
  ON calendar.id = category.calendar_id
 AND calendar.status = 'ACTIVE'
 AND calendar.deleted = FALSE
WHERE schedule_row.deleted = FALSE
  AND schedule_row.member_id <> COALESCE(calendar.owner_member_id, category.member_id);
```

Do not revoke an editor or deploy owner-policy changes until these rows are covered by the central
access decision. The category owner must manage them, while a former editor must not retain access
solely because `schedules.member_id` records the author.

Account withdrawal follows the same distinction. An active calendar owner must transfer or archive
before withdrawal. Withdrawing a non-owner must not delete rows they authored inside another
owner's category/calendar. If a personal-category owner withdraws, a foreign-authored schedule is
detached from the deleted canonical category and retained as standalone author-owned content; its
category snapshot is presentation data, not a live ACL parent.

### Calendar ownership authority drift

Ownership transfer reassigns direct schedule/category grants, revokes `PENDING` invitations for the
calendar and everything inside it, and reassigns `ACCEPTED` invitation owners so an accepted-token
retry remains idempotent. The following query must return zero mismatches for active calendars.

```sql
SELECT
    resource_type,
    invitation_status,
    COUNT(*) AS invitation_owner_mismatch_count
FROM (
    SELECT
        'CALENDAR' AS resource_type,
        invitation.status AS invitation_status,
        invitation.owner_member_id AS stored_owner_member_id,
        calendar.owner_member_id AS current_owner_member_id
    FROM schedule_share_invitations invitation
    JOIN schedule_calendars calendar
      ON invitation.resource_type = 'CALENDAR'
     AND invitation.resource_id = calendar.id
    WHERE invitation.status IN ('PENDING', 'ACCEPTED')
      AND invitation.deleted = FALSE
      AND calendar.status = 'ACTIVE'
      AND calendar.deleted = FALSE

    UNION ALL

    SELECT
        'CATEGORY',
        invitation.status,
        invitation.owner_member_id,
        calendar.owner_member_id
    FROM schedule_share_invitations invitation
    JOIN schedule_categories category
      ON invitation.resource_type = 'CATEGORY'
     AND invitation.resource_id = category.id
     AND category.deleted = FALSE
    JOIN schedule_calendars calendar
      ON calendar.id = category.calendar_id
     AND calendar.status = 'ACTIVE'
     AND calendar.deleted = FALSE
    WHERE invitation.status IN ('PENDING', 'ACCEPTED')
      AND invitation.deleted = FALSE

    UNION ALL

    SELECT
        'SCHEDULE',
        invitation.status,
        invitation.owner_member_id,
        calendar.owner_member_id
    FROM schedule_share_invitations invitation
    JOIN schedules schedule_row
      ON invitation.resource_type = 'SCHEDULE'
     AND invitation.resource_id = schedule_row.id
     AND schedule_row.deleted = FALSE
    LEFT JOIN schedule_categories category
      ON category.id = schedule_row.category_id
     AND category.deleted = FALSE
    JOIN schedule_calendars calendar
      ON calendar.id = COALESCE(schedule_row.calendar_id, category.calendar_id)
     AND calendar.status = 'ACTIVE'
     AND calendar.deleted = FALSE
    WHERE invitation.status IN ('PENDING', 'ACCEPTED')
      AND invitation.deleted = FALSE
) invitation_authority
WHERE stored_owner_member_id <> current_owner_member_id
GROUP BY resource_type, invitation_status;
```

A `PENDING` mismatch means the old owner's link was not revoked or a move did not transfer its
management owner. An `ACCEPTED` mismatch can make a legitimate recipient's idempotent retry fail even
though the active grant still exists. Never repair these rows without checking the matching grant,
acceptance ledger, and resource lifecycle in the same snapshot.

## 2. Reconciliation decision

Classify every `legacy_category_id` calendar before writing data.

| Class | Evidence | Action |
| --- | --- | --- |
| Not migrated | No generated calendar | Do not run the old auto-conversion backfill |
| Generated and untouched | Only the legacy category, owner, copied recipients; no calendar links | Reconcile category grant as canonical in an approved migration |
| Adopted by user | Extra category/member/link or changed behavior | Do not auto-detach; migrate with an explicit per-calendar decision |

A reconciliation migration must be version-marked, fail closed on unexpected counts, run with old
writers stopped, and produce before/after fingerprints. It must never print private event or token
data. The exact DML depends on the production classification and therefore belongs in a separately
reviewed change, not this runbook.

## 3. Deployment gates

1. Deploy a server that accepts category grants and pending category links for both personal and
   calendar-bound categories.
2. Verify effective access, schedule write authorization, cache audience, and push audience use the
   same union of schedule, category, and calendar grants, and use resource owner rather than author
   for container content.
3. Run the read-only inventory and classify every generated calendar.
4. Reconcile duplicate legacy memberships in a quiesced, version-marked migration.
5. Verify `schedule_category_scope_mismatch = 0`, no pending link lost its live resource, and the
   ownership-authority query returns no rows.
6. Verify editor withdrawal preserves container content, previous-owner withdrawal after transfer
   preserves calendar content, and personal-category owner withdrawal detaches rather than deletes
   foreign-authored schedules.
7. Run two-account direct-share and link-share E2E, including category movement and exact gained/lost
   audience preview counts.
8. Keep the old read path until audit counts and client telemetry show no unexpected access loss.

Rollback must restore the application and its exact matching data policy. A binary rollback to a
server that rejects calendar-bound category links is unsafe after those links have been issued.
