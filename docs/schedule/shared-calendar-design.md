# Shared Calendar Design

Status: Base calendar domain implemented; sharing-policy v1 alignment in progress

Last updated: 2026-09-09 KST

## 1. Context

The product exposes schedule, category, and calendar as three first-class sharing scopes. Each scope
supports `VIEWER` and `EDITOR`; `OWNER` is derived and is never granted through a normal share
request. The normative product and movement rules are in
[`schedule-sharing-policy-v1.md`](./schedule-sharing-policy-v1.md).

The shared-calendar design keeps the scopes composable:

- A calendar owns membership and access.
- A category remains a movable classification group owned by either the personal calendar or one shared calendar,
  and may also have its own direct grants.
- A schedule belongs to at most one shared calendar.
- A member's origin, route, notification settings, and departure state remain member-owned data.

NoLate does not add attendance confirmation. Joining a shared calendar means that every active
member can see every active schedule in that calendar.

## 2. Sharing Axes

Sharing scope and schedule type are independent axes.

| Axis | Values |
| --- | --- |
| Sharing scope | One schedule / Category / Shared calendar |
| Schedule type | Normal / Route |
| Shared content | Schedule only / Schedule and travel |

`SCHEDULE_AND_TRAVEL` never copies the owner's route. It grants each recipient the ability to save
their own `ScheduleTravelPlan` and exposes member travel status according to role policy.

## 3. Domain Model

### ScheduleCalendar

- Owns name, color, lifecycle status, and default content mode.
- Has exactly one active `OWNER` member.
- Is the explicit access boundary for schedules.
- Uses optimistic versioning for concurrent settings changes.

### ScheduleCalendarMember

- Unique by `(calendar_id, member_id)`.
- Has `OWNER`, `EDITOR`, or `VIEWER` role.
- Has `ACTIVE`, `LEFT`, or `REMOVED` status.
- A revoked member row is reactivated instead of duplicated.

### Schedule

- Has `NORMAL` or `ROUTE` type.
- May have `calendar_id`; `null` means it is not in a shared calendar.
- May override the calendar's default content mode.
- Uses a category from the same calendar scope. A personal schedule uses a personal category;
  a shared-calendar schedule uses a category owned by that calendar.

### ScheduleCategory

- `calendar_id = null` means a personal-calendar category.
- A non-null `calendar_id` makes the category visible to active calendar members.
- `OWNER` and `EDITOR` may create, edit, reorder, and delete calendar categories; `VIEWER` is read-only.
- `member_id` remains the creator/audit field and is not the access boundary for a calendar category.

### ScheduleCategoryShare

- Represents ongoing access to the category and all current or future schedules assigned to it.
- Supports `VIEWER` and `EDITOR` as a first-class product feature; it is not a temporary compatibility
  path.
- Coexists with calendar membership. A category recipient sees only that category unless another
  grant widens access.
- Category movement preserves the category id, active grants, and valid invitation links. Calendar
  audience changes do not silently revoke the category grant.
- A category `EDITOR` can create, update, and delete schedules in that category but cannot manage
  sharing or move content to another access scope.

### ScheduleShare

- Continues to represent one-off schedule access.
- Stores `SCHEDULE_ONLY` or `SCHEDULE_AND_TRAVEL` per recipient.
- Coexists with calendar membership. Revoking one grant must not remove access granted by another.

### ScheduleTravelPlan

- Remains unique by `(schedule_id, member_id)`.
- Is created only when the member saves a route; calendar join never pre-creates plans.
- Is readable in full by its owner and by schedule/calendar owners or editors.
- Is hidden when no effective travel grant exists, without being destructively deleted.

## 4. Effective Access

All schedule endpoints use one policy result instead of querying share tables independently.

```text
resourceOwner =
    activeCalendar.owner
    OR activePersonalCategory.owner
    OR standaloneSchedule.author

canView =
    member == resourceOwner
    OR hasActiveDirectShare
    OR hasActiveCategoryShare
    OR isActiveCalendarMember

travelEnabled =
    schedule.type == ROUTE
    AND (
        activeDirectShare.contentMode == SCHEDULE_AND_TRAVEL
        OR effectiveCalendarContentMode == SCHEDULE_AND_TRAVEL
    )

canEdit =
    member == resourceOwner
    OR strongestActiveRole == EDITOR
    OR strongestActiveRole == OWNER

canViewAllTravelPlans =
    member == resourceOwner
    OR strongestActiveRole == EDITOR
    OR strongestActiveRole == OWNER
```

Direct, category, and calendar grants are combined by strongest permission and widest content mode.
Results are de-duplicated by member id before producing travel status or notifications.

For schedules created by a category `EDITOR`, authorship does not become an irrevocable personal
grant. The category owner manages the content, the author remains visible for audit/display, and the
author loses access when all effective grants are removed. Code that treats `schedules.member_id` as
an unconditional owner shortcut does not satisfy the v1 policy.

`schedule_category_snapshots` are immutable presentation/audit data. A snapshot must not resurrect a
deleted category as an ACL boundary. If account withdrawal detaches a foreign-authored schedule from
a deleted personal category, the canonical category id is cleared and the now-standalone schedule is
owned by its author; the retained snapshot remains display-only.

## 5. Route Setup Policy

`routeSetupRequired` is a response projection, not durable business state.

It is true only when all conditions hold:

1. The schedule is a route schedule.
2. Effective content mode includes travel.
3. The schedule starts after now and no later than 72 hours from now.
4. The current member has no valid travel plan for the current schedule fingerprint.
5. The schedule and effective access are active.

Before the 72-hour window the route editor remains available, but the UI does not show a warning.
When time or destination changes, the fingerprint changes and an existing plan becomes `STALE`.

The missing-route reminder is emitted once per `(member, schedule, schedule fingerprint)`. Multiple
missing schedules for one member in the same worker cycle are grouped into one push while each
schedule remains individually addressable in the in-app inbox.

`routeReminderEnabled` is member-owned, including for the calendar owner. Calendar role management
cannot modify another member's preference; each member changes it only through the preferences API.

## 6. Lifecycle Rules

### Content mode change

- `SCHEDULE_ONLY -> SCHEDULE_AND_TRAVEL`: only schedules already inside the 72-hour window become
  reminder candidates.
- `SCHEDULE_AND_TRAVEL -> SCHEDULE_ONLY`: cancel member push jobs and immediately hide shared travel
  status. Keep member-owned plans so re-enabling travel is non-destructive.
- A push worker re-checks effective travel access immediately before provider delivery, covering a
  worker that was claimed just before the content-mode transaction committed.
- A direct travel grant can keep travel enabled after the calendar default is reduced.

### Member removal

- Revoke calendar access immediately.
- Cancel that member's jobs for schedules whose remaining grants do not include travel.
- Keep member-owned travel plans private.
- Existing schedules remain in the calendar.

### Owner leave

- An owner cannot leave or be removed while still owning the calendar.
- Ownership transfer locks the calendar and both member rows and leaves exactly one active owner.
- Ownership transfer keeps existing direct schedule/category grants but reassigns their management
  owner to the new calendar owner.
- It revokes every `PENDING` invitation for the calendar, its categories, and its schedules; the new
  owner creates fresh links.
- It retains `ACCEPTED` invitations and their acceptance ledgers, and reassigns their management
  owner so a prior recipient's same-token retry remains idempotent while the matching grant or
  membership remains active.

### Account withdrawal

- An active calendar owner must transfer ownership or archive the calendar before withdrawal.
- A withdrawing calendar member or direct category editor leaves/revokes their access and personal
  travel/notification state, but categories and schedules authored inside another owner's container
  remain container content. The author reference remains for audit after account anonymization.
- Withdrawing the owner of a personal category deletes that category and owner-owned content. A
  schedule authored by another member is retained, detached from the deleted canonical category,
  and becomes a standalone author-owned schedule; its immutable category snapshot is display-only.
- A former calendar owner may withdraw after transfer without deleting calendar categories or
  schedules that still carry the former owner's creator id.

### Calendar archive

- Archive is soft deletion; it immediately removes effective access and cancels related jobs.
- Schedules are not silently deleted.
- Permanent deletion is outside this implementation.

## 7. Concurrency Contract

Lock acquisition order is part of the application contract.

1. Multi-member mutations lock every affected member row first, in ascending member id order. An
   unlocked preview may discover ids, but the mutation must re-check the participant set after the
   parent lock and fail with a retryable conflict if that set expanded.
2. The shared-resource parent order is schedule rows, calendars in ascending id order, then
   categories. A category-bound calendar operation therefore locks `calendar -> category`; a
   personal-category operation starts at the category row.
3. Dependent rows always follow `category -> invitation -> share`. Invitation acceptance may use an
   unlocked token lookup only to discover the parent id and must re-read the invitation with
   `FOR UPDATE` after the parent lock. Category movement, deletion, acceptance, and ownership
   transfer use this same order.
4. Schedule moves lock the schedule before the source and destination parents. Ownership transfer
   freezes contained schedules before the calendar, categories, invitations, and direct grants.
5. Travel-plan writes lock the schedule before reading effective access and writing the plan/job.
6. Reminder workers claim outbox rows; business transactions never call a push provider directly.

Unique constraints are the final duplicate defense. `@Version` detects concurrent updates where
serialization is unnecessary. API conflicts are returned as HTTP 409 rather than silently applying
the last write.

Critical concurrency tests are included as MySQL 8 Testcontainers tests and run automatically when
Docker is available. H2 tests remain useful for fast unit and repository feedback but are not
accepted as proof of MySQL lock behavior. On 2026-07-23 the local Docker daemon was unavailable, so
the three MySQL tests compiled and were explicitly skipped; CI or staging execution remains required.

Spring concurrency failures are exposed as HTTP `409` with error code `C003`, allowing clients to
reload current calendar state instead of treating an optimistic lock or deadlock victim as a server
failure.

## 8. Compatibility Migration

The July/August 2026 migrations were written for an earlier plan that converted category sharing to
calendar membership. The current product policy retains both scopes, so that backfill cannot be
treated as canonical or rerun without inspection. In particular, copied calendar memberships can
keep access alive after a category share is revoked and can widen access when another category is
added to the generated calendar.

Use [`category-sharing-reconciliation-runbook.md`](./category-sharing-reconciliation-runbook.md) to
inventory generated calendars, duplicate grant paths, pending category links, and author/owner rows.
Do not automatically delete either grant for a generated calendar that a user may already have
adopted.

Category and contained-schedule invitations keep their resource identity when the category moves.
`PENDING` links remain usable, `ACCEPTED` links keep their acceptance ledger and idempotent retry, and
their management owner changes to the destination calendar owner. Accepted-token retry assumes the
matching grant remains active. Moving a category is therefore different from transferring calendar
ownership, which revokes all pending links inside the calendar.

## 9. Initial API Surface

```http
POST   /api/schedule-calendars
GET    /api/schedule-calendars
GET    /api/schedule-calendars/{calendarId}
PATCH  /api/schedule-calendars/{calendarId}
DELETE /api/schedule-calendars/{calendarId}

GET    /api/schedule-calendars/{calendarId}/members
POST   /api/schedule-calendars/{calendarId}/members
PATCH  /api/schedule-calendars/{calendarId}/members/{memberId}
DELETE /api/schedule-calendars/{calendarId}/members/{memberId}
PATCH  /api/schedule-calendars/{calendarId}/preferences
POST   /api/schedule-calendars/{calendarId}/ownership
POST   /api/schedule-calendars/{calendarId}/leave

POST   /api/schedule-calendars/{calendarId}/invitations
GET    /api/schedule-calendars/{calendarId}/invitations
```

Existing schedule share endpoints add `contentMode`. Existing invitation acceptance remains the
single token endpoint and dispatches by resource type.

The historical production migrations are:

- `docs/schedule/migrations/2026-07-23-shared-calendars.sql`
- `docs/schedule/migrations/2026-08-10-calendar-scoped-categories.sql`

Any reconciliation must be a new version-marked migration with a quiesced writer window and
before/after verification; neither historical file is the rollback for the current policy.

## 10. Non-goals

- Attendance responses such as going, maybe, or declined
- Comments, albums, attachments, and calendar-specific profiles
- Public calendars
- Permanent calendar deletion
- Copying one member's route to another member
- Merging two category identities during a move
- Extracting a shared-calendar category into a personal calendar
