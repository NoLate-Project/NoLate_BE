# Schedule sharing production availability contract

> 이 파일명은 이전 production-off 출시 증거의 추적성을 위해 유지한다. 현재 소스 계약은
> 공유 기본 활성화와 명시적 kill switch다.

## Authority

`ScheduleSharingAvailabilityPolicy` is the single server-side authority. Sharing is enabled only
when `schedule.sharing.enabled` resolves to the exact lowercase string `true`. The checked-in local
and production runtime default is `true`; an explicit `false`, whitespace, case variant, or typo
disables the feature in every profile.

`SCHEDULE_SHARING_ENABLED=false` is the production kill switch. It closes every sharing API and
changes native schedule authorization and visibility to owner-only without deleting dormant rows.
All instances in a rollout must use the same value.

Each application instance emits one value-free startup record:

```text
Schedule sharing availability initialized. state=ENABLED
```

An enabled deployment must confirm `ENABLED` for every API, scheduler, and outbox-drainer
instance. Instance or pod identity belongs to the platform log envelope; the application record
contains no member, schedule, invitation, device, or token value.

## Dormant data

Activating the kill switch does not revoke, delete, or rewrite existing direct shares, category
shares, calendar memberships, or invitations. They remain dormant until sharing is enabled again.
Ordinary resource lifecycle cleanup (for example, deleting an account or its owned resource) still
applies independently.

While disabled:

- every share, invitation, and shared-calendar API returns the stable `C006 FEATURE_DISABLED`
  response;
- native schedule/category visibility and service authorization are owner-only;
- participant travel, editor mutation, departure nudge, reminder, and backfill paths cannot use a
  dormant grant;
- stored share-derived inbox/history rows and currently unauthorized resource notifications are
  hidden and excluded from unread counts;
- new share notification events are not created;
- queued share-derived deliveries fail the final recipient fence and become terminal without a
  provider call.

Owner access to the owner's schedules, ETA, travel plan, and ordinary schedule notifications stays
enabled. Push event keys, frozen manifests, per-device attempts, token ownership leases, and
at-most-once handling are unchanged.

## Cache isolation

Schedule calendar Redis entries use separate `owned` and `shared` visibility namespaces. A
kill-switch rollout can therefore keep using Redis without reading a DTO created while sharing was
enabled. The common revision is durable authority in the FK-free
`schedule_calendar_cache_revisions` table. Events in one transaction are coalesced, then a terminal
`BEFORE_COMMIT` synchronization locks the complete revision-row audience once in ascending member
ID order and increments it in the same transaction as each schedule, direct/category share,
invitation, calendar-membership, travel-plan, or withdrawal mutation. These independent lock rows
never acquire a member row; durable outbox listeners run earlier so no later DB lock reverses the
order. Cache enablement does not disable this revision fence. The revision returned to clients also
includes the visibility scope, so an app clears its in-memory calendar cache when the deployment
setting changes even if no schedule row changed. Missing revision rows fail closed: Redis is
bypassed for the calendar lookup and the revision endpoint does not return a reusable revision 0.
Default/local profiles run an idempotent missing-row backfill before accepting application traffic
so an existing development database is upgraded too; concurrent startup duplicate races retry from
a fresh statement snapshot. The `prod` profile excludes that initializer and accepts only the
offline migration marker as authority.

Redis stores only TTL-bound month JSON. Legacy `v1` entries are ignored by the `v2` key namespace
and expire by their existing TTL. Redis loss, eviction, or failover cannot reset the durable
generation or make an older generation addressable again; it only turns the next lookup into a DB
miss-and-fill. Production must apply
`docs/schedule/migrations/2026-07-27-schedule-calendar-cache-revision.sql` before starting this
version, and the production schema guard requires its verified marker.

## Rollout and rollback

Apply the additive durable-revision migration and complete its all-member backfill while every old
API and worker instance is stopped. A mixed rollout is forbidden: old instances write only the
legacy Redis revision and cannot invalidate the new DB authority. Deploy only the new store build
that reads DB generations and the scoped Redis `v2` namespace with the `prod` profile, verify every
instance reports the same startup state, then exercise direct schedule, category, invitation,
shared-calendar, edit/revoke, notification, Redis failover, and calendar cache smoke tests.

Emergency rollback may set `SCHEDULE_SHARING_ENABLED=false` while leaving all share rows untouched.
Before enabling production sharing, the release owner must still complete the store UGC obligations
tracked in the canonical release roadmap, including reporting, blocking/filtering, moderation
response, and verified invitation-link behavior.

A binary rollback to a build that still treats the legacy Redis `v1` revision as authority is not
a normal rolling restart. After any new writer has committed, the old build cannot observe the DB
generation. Start the old binary with `SCHEDULE_CALENDAR_CACHE_ENABLED=false`, or keep traffic
closed until every legacy `v1` month key has expired or a targeted purge is verified. Setting only
`SCHEDULE_SHARING_ENABLED=false` does not make the old cache safe.

Actual load-balancer routing, mixed-version instance exclusion, Redis failover, MySQL 8
multi-instance execution, and real FCM/APNs delivery remain staging/production gates.
