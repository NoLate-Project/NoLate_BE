# Shared Calendar Design

Status: Implemented in feature branches; staging migration pending

Last updated: 2026-07-23 KST

## 1. Context

NoLate currently supports sharing one schedule and sharing every schedule that belongs to an
owner-owned category. Category sharing is useful as a compatibility feature, but it makes a label
act as an access-control boundary. Moving a schedule between categories can therefore grant or
revoke access without the user performing an explicit sharing action.

The shared-calendar design separates these concerns:

- A calendar owns membership and access.
- A category remains a color and classification label.
- A schedule belongs to at most one shared calendar.
- A member's origin, route, notification settings, and departure state remain member-owned data.

NoLate does not add attendance confirmation. Joining a shared calendar means that every active
member can see every active schedule in that calendar.

## 2. Sharing Axes

Sharing scope and schedule type are independent axes.

| Axis | Values |
| --- | --- |
| Sharing scope | One schedule / Shared calendar |
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
- Keeps its category independently from its calendar.

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
canView =
    ownsSchedule
    OR hasActiveDirectShare
    OR isActiveCalendarMember

travelEnabled =
    schedule.type == ROUTE
    AND (
        activeDirectShare.contentMode == SCHEDULE_AND_TRAVEL
        OR effectiveCalendarContentMode == SCHEDULE_AND_TRAVEL
    )

canEdit =
    ownsSchedule
    OR strongestActiveRole == EDITOR
    OR strongestActiveRole == OWNER

canViewAllTravelPlans =
    ownsSchedule
    OR strongestActiveRole == EDITOR
    OR strongestActiveRole == OWNER
```

Direct and calendar grants are combined by strongest permission and widest content mode. Results
are de-duplicated by member id before producing travel status or notifications.

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
- Ownership transfer revokes pending links issued by the previous owner; the new owner creates fresh links.

### Calendar archive

- Archive is soft deletion; it immediately removes effective access and cancels related jobs.
- Schedules are not silently deleted.
- Permanent deletion is outside this implementation.

## 7. Concurrency Contract

Lock acquisition order is part of the application contract.

1. Calendar invitation creation, acceptance, ownership transfer, and archive lock the calendar row
   first, then invitation rows in ascending id order. Acceptance uses an unlocked token lookup only
   to discover the parent id and re-reads the invitation with `FOR UPDATE` before validation.
2. Other calendar mutations lock the calendar row before member rows.
3. Multi-member operations lock member rows in ascending member id order.
4. Schedule moves lock the schedule, then involved calendars in ascending calendar id order.
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

Migration is additive and reversible.

1. Create calendar and membership tables and nullable schedule columns.
2. Create one shared calendar for each actively shared category.
3. Copy active category shares to calendar members.
4. Attach schedules in those categories to the created calendars while retaining category ids.
5. Treat pending category invitations as legacy invitations that create calendar membership.
6. Dual-read category shares and calendar membership during the FE rollout.
7. Stop creating category shares after the FE cutover.
8. Remove the legacy path only after production verification.

Existing category shares use `SCHEDULE_AND_TRAVEL` during backfill to preserve current behavior.

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

The additive production migration is `docs/schedule/migrations/2026-07-23-shared-calendars.sql`.

## 10. Non-goals

- Attendance responses such as going, maybe, or declined
- Comments, albums, attachments, and calendar-specific profiles
- Public calendars
- Permanent calendar deletion
- Copying one member's route to another member
