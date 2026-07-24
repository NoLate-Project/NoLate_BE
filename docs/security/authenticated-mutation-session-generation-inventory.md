# Authenticated mutation session-generation inventory

Reviewed: 2026-07-25

Scope: every `POST`, `PUT`, `PATCH`, and `DELETE` controller under `src/main`, plus
authenticated `GET` handlers whose service may lazily create data.

## Required boundary

An access-authenticated mutation passes the signed
`MemberPrincipal.accessTokenSessionGeneration` to the transaction that performs the first
database write. That transaction locks the actor member row first, verifies that the member is
active and that the stored generation exactly equals the presented generation, and only then
writes domain rows. Multi-member operations lock all participating member rows in ascending
member-id order before resource/job/outbox rows.

Controller-only validation is insufficient: a g1 request may pass the security filter, pause,
and resume after logout or login g2 commits.

## Access-authenticated mutation inventory

Every endpoint in this table is generation-fenced. Endpoints sharing a cell use the same writer
boundary named in the last column.

| Family | HTTP endpoint(s) | First-write fence |
| --- | --- | --- |
| Member | `PATCH /api/member/curation/complete`; `GET /api/member/profile` (legacy default profile may be created); `PUT /api/member/profile`; `PATCH /api/member/password` | `MemberService.getActiveMemberForUpdate` in the enclosing member use-case transaction |
| Push token/test | `POST /api/notifications/token`; `POST /api/notifications/test/send` | token registration writer / durable outbox writer lock the member and compare the signed generation before token/source/manifest writes; test-send restores the same fence for claim, provider lease, and redrive |
| Notification inbox | `PATCH /api/notifications/{notificationId}/read`; `PATCH /api/notifications/read-all` | `AppNotificationService` locks the member and compares generation before the notification update |
| Favorite categories | `POST /api/favorite-place-categories`; `PATCH /api/favorite-place-categories/{categoryId}`; `DELETE /api/favorite-place-categories/{categoryId}`; `PATCH /api/favorite-place-categories/reorder` | `FavoritePlaceService` calls `MemberService.getActiveMemberForUpdate` in the same transaction |
| Favorite places | `PUT /api/favorite-places/default-origin`; `DELETE /api/favorite-places/default-origin`; `POST /api/favorite-places`; `PATCH /api/favorite-places/{placeId}`; `DELETE /api/favorite-places/{placeId}`; `PATCH /api/favorite-places/{placeId}/default-origin`; `PATCH /api/favorite-places/reorder` | `FavoritePlaceService` calls `MemberService.getActiveMemberForUpdate` in the same transaction |
| Recent route places | `POST /api/recent-route-places`; `DELETE /api/recent-route-places/{recentPlaceId}` | `RecentRoutePlaceService` calls `MemberService.getActiveMemberForUpdate` in the same transaction |
| Schedules | `POST /api/schedules`; `POST /api/schedules/import`; `PUT /api/schedules/{scheduleId}`; `DELETE /api/schedules/{scheduleId}`; `POST /api/schedules/{scheduleId}/depart-now`; `POST /api/schedules/{scheduleId}/departure-reminder/snooze` | schedule use-case writer transaction |
| Schedule categories | `GET /api/schedule-categories` (default categories may be created); `POST /api/schedule-categories`; `PATCH /api/schedule-categories/{categoryId}`; `DELETE /api/schedule-categories/{categoryId}`; `PATCH /api/schedule-categories/reorder` | schedule-category writer transaction |
| Schedule shares | `POST /api/schedules/{scheduleId}/shares`; `PATCH /api/schedules/{scheduleId}/shares/{shareId}`; `DELETE /api/schedules/{scheduleId}/shares/{shareId}`; `POST /api/schedules/{scheduleId}/shares/invitations`; `DELETE /api/schedules/{scheduleId}/shares/invitations/{invitationId}` | schedule-share writer transaction |
| Category shares | `POST /api/schedule-categories/{categoryId}/shares`; `PATCH /api/schedule-categories/{categoryId}/shares/{shareId}`; `DELETE /api/schedule-categories/{categoryId}/shares/{shareId}`; `POST /api/schedule-categories/{categoryId}/shares/invitations`; `DELETE /api/schedule-categories/{categoryId}/shares/invitations/{invitationId}` | schedule-share writer transaction |
| Share acceptance | `POST /api/share-invitations/{token}/accept` | invitation acceptance writer transaction |
| Shared calendars | `POST /api/schedule-calendars`; `PATCH /api/schedule-calendars/{calendarId}`; `DELETE /api/schedule-calendars/{calendarId}`; `POST /api/schedule-calendars/{calendarId}/members`; `PATCH /api/schedule-calendars/{calendarId}/members/{memberId}`; `PATCH /api/schedule-calendars/{calendarId}/preferences`; `DELETE /api/schedule-calendars/{calendarId}/members/{memberId}`; `POST /api/schedule-calendars/{calendarId}/leave`; `POST /api/schedule-calendars/{calendarId}/ownership`; `POST /api/schedule-calendars/{calendarId}/invitations`; `DELETE /api/schedule-calendars/{calendarId}/invitations/{invitationId}` | shared-calendar/share writer transaction; multi-member locks use ascending member id |
| Travel/departure | `PUT /api/schedules/{scheduleId}/travel-plans/my`; `POST /api/schedules/{scheduleId}/departure-nudges/{targetMemberId}` | travel-plan / departure-nudge writer transaction |

## Intentional special paths and non-writes

| Type | HTTP endpoint(s) | Why the standard access-generation boundary is not used |
| --- | --- | --- |
| Account bootstrap | `POST /api/member/auth/sign-up`; `POST /api/member/auth/login`; `POST /api/member/auth/sns-login`; `POST /api/member/auth/sns-registration`; `POST /api/member/auth/sns-sign-up`; `POST /api/member/auth/token-login`; `POST /api/member/auth/refresh` | No access-token principal exists yet. The presented credential/refresh session is validated and a new monotonic generation is issued or rotated. |
| Logout | `POST /api/member/auth/logout` | Refresh-session compare-and-revoke boundary. A valid old logout is an idempotent no-op and cannot revoke a newer login generation. |
| Withdrawal | `DELETE /api/member/withdraw` | Still generation-fenced, but intentionally uses `AccountCleanupService.lockWithdrawalFence` rather than the actor-only helper. It locks the owner and all cleanup participants in global id order before invalidation and deletion. |
| Command-shaped reads | `POST /api/schedules/parse`; `POST /api/routes/quick-share/options`; `POST /api/routes/transit` | Calls parsing/route providers and does not write application state. |
| Non-production diagnostics | `POST /api/dev/push-scenarios/run`; `POST /api/dev/schedule-push-scenarios/run` | Both are opt-in diagnostics. Controllers/runners are excluded by `@Profile("!prod")`; production configuration hard-disables both properties. |

## Regression rule

Any new access-authenticated mutation fails review unless:

1. its controller-to-writer call chain visibly carries the signed generation;
2. the actual write transaction locks and validates the member before its first write; and
3. a latch/integration test proves `g1 authenticated -> g2 committed -> g1 resumed` produces
   zero domain writes (and, for push, zero provider calls).

Repeat the inventory with:

```bash
grep -R -n -E '@(Post|Put|Patch|Delete)Mapping|@AuthenticationPrincipal' \
  src/main/kotlin/com/noLate --include='*Controller.kt'
```
