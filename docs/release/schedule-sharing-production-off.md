# Schedule sharing production-off contract

## Authority

`ScheduleSharingAvailabilityPolicy` is the single server-side authority. Sharing is enabled only
when `schedule.sharing.enabled` is exactly the lowercase string `true` and the `prod` profile is not
active. Missing values, `false`, whitespace, case variants, typos, and every production-profile
override are disabled.

The store production profile declares `schedule.sharing.enabled: false` as configuration evidence,
while the profile check in code is the final guard against higher-precedence CLI, system-property,
or environment overrides.

Each application instance emits one value-free startup record:

```text
Schedule sharing availability initialized. state=DISABLED
```

Deployment verification must confirm `DISABLED` for every API, scheduler, and outbox-drainer
instance. Instance or pod identity belongs to the platform log envelope; the application record
contains no member, schedule, invitation, device, or token value.

## Dormant data

Activating production-off does not revoke, delete, rewrite, or reactivate existing direct shares,
category shares, calendar memberships, or invitations. They remain dormant so a separately
reviewed future release can define an explicit reactivation policy. Ordinary resource lifecycle
cleanup (for example, deleting an account or its owned resource) still applies independently.

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

## Rollout and rollback

No schema or destructive data migration is required. Deploy the store build with the `prod`
profile, verify every instance's startup state, then exercise owner-only schedule and notification
smoke tests plus negative direct API probes for sharing.

Rollback means rolling the application forward or back while leaving all share rows untouched.
Re-enabling sharing is not an operational toggle in production: it requires a reviewed build whose
policy and store UGC obligations explicitly permit it.

Actual load-balancer routing, mixed-version instance exclusion, Redis cache behavior, MySQL 8
multi-instance execution, and real FCM/APNs provider suppression remain staging/production gates.
