# Push and ETA observability runbook

## Scope and release boundary

This source adds an operator-neutral Micrometer/Prometheus contract for push delivery and ETA
workers. It does not create a Prometheus server, dashboard, paging route, Sentry project, or
Crashlytics project. The rule file at
[`ops/prometheus/nolate-release-alerts.yml`](../../ops/prometheus/nolate-release-alerts.yml) is a
reviewable draft and is not evidence that an external monitoring system has loaded it.

The custom deployment probes remain unchanged:

- `GET /health`
- `GET /health/liveness`
- `GET /health/readiness`

They expose only application availability. Actuator health, metrics inventory, environment, and
every other management endpoint remain denied by endpoint identity, including when an operator
changes the Actuator base path or an endpoint path mapping.

Actuator health is additionally removed from HTTP endpoint discovery by a code-level web filter.
This is not overridable through
`MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,prometheus` or
`MANAGEMENT_ENDPOINT_HEALTH_ACCESS=unrestricted`; it prevents root or empty Actuator base paths
from registering a competing `/health*` handler. The filter is web-specific, so it does not change
JMX endpoint discovery. On a separate management connector, `/health*` is reserved and denied;
the three custom probes remain available only on the application connector. Remapping another
exposed endpoint or the Actuator discovery base onto one of these reserved paths fails application
startup with an explicit collision error.

## Required trust migrations

This release is not compatible with a rolling schema change. Stop API and worker instances, apply
and verify the reviewed migrations in order, then start the new binary:

1. `docs/schedule/migrations/2026-07-29-departure-alarm-mode.sql`
2. `docs/schedule/migrations/2026-07-29-departure-alarm-sync.sql`
3. `docs/schedule/migrations/2026-07-31-push-eta-trust.sql`
4. `docs/schedule/migrations/2026-08-01-departure-alarm-fire-evidence.sql`
5. `docs/schedule/migrations/2026-08-01-departure-alarm-schedule-receipts.sql`
6. `docs/schedule/migrations/2026-08-01-push-delivery-ack-capability.sql`
7. `docs/schedule/migrations/2026-08-04-schedule-route-optimistic-lock.sql`
8. `docs/schedule/migrations/2026-08-04-departure-alarm-plan-v2.sql`

Production startup requires exactly one marker for each migration. A missing predecessor, partial
table, failed postcondition, or attempted reapplication fails closed for operator inspection.

When `schedule.push.enabled=true` in production, startup also fails closed unless the complete
delivery and ETA path is configured: scheduler, durable push outbox, Firebase, TMAP plus its server
key for non-transit modes, ODsay plus its server key for transit, Seoul subway/bus arrival keys,
and a TAGO bus arrival key. Do not bypass this guard to run a fallback-only notification cohort;
disable schedule push explicitly until the required providers are ready.

## Scrape access

The Prometheus registry is enabled, but the HTTP scrape route fails closed. Missing, false, and
malformed values do not open it. The only public actuator request that can be enabled is `GET` on
the Prometheus endpoint identity. With the default mapping this is exactly
`GET /actuator/prometheus`; a configured base path or Prometheus path mapping moves the allowed
route without allowing a different endpoint mapped to the old URL.

```text
OBSERVABILITY_PROMETHEUS_PUBLIC_ENABLED=true
```

Set this value only after the deployment ingress or private network restricts the path to the
approved scraper. Do not expose an unauthenticated scrape route to the public Internet. A normal
member JWT cannot unlock management endpoints when the flag is off, and enabling the flag does not
open POST, OPTIONS, a trailing-slash subpath, Actuator health, or Actuator metrics inventory. The
same endpoint-aware security chain is integration-tested on a separate management port.

To close the route, remove the value or set it to anything other than exact lowercase `true`, then
restart the application.

## Metric contract

All auto-configured meter registries are allowlisted to the application-owned `nolate.*` namespace.
Spring/JVM/HTTP meters outside that namespace are intentionally denied at registration time, not
merely hidden at the HTTP response, so they are unavailable for internal dashboards as well as the
public scrape. This prevents dependency-added exception, URI, repository, pool, or similar
dimensions from silently expanding the public contract. Every exported meter has the stable
`application` common tag. All metric-specific tags are finite enums controlled by source code. No
exported metric tag contains a member, schedule, calendar, token, device, provider message ID,
exception class, error message, title, body, or raw payload.

| Metric | Type | Bounded dimensions | Meaning |
|---|---|---|---|
| `nolate_push_delivery_claims_total` | counter | `outcome` | Durable delivery claim result, including pre-existing ambiguous boundaries |
| `nolate_push_delivery_uncertain_total` | counter | `reason` | Provider-unknown or locally unrecorded terminal outcomes |
| `nolate_push_provider_duration_seconds` | histogram/timer | `outcome` | FCM/provider call result and latency |
| `nolate_push_token_lease_total` | counter | `outcome` | Token ownership lease acquired, busy, deferred, or superseded |
| `nolate_push_outbox_events_total` | counter | `outcome` | Claim, completion, retry, terminal failure, deferral, recovery, or lost lease |
| `nolate_push_client_acks_total` | counter | `stage`, `outcome` | Authenticated, account/device-bound client lifecycle ACKs; `outcome` is recorded or duplicate |
| `nolate_push_client_ack_latency_seconds` | histogram/summary | `stage` | Server-observed duration from durable provider success to the first ACK received by the server |
| `nolate_departure_alarm_fire_events_total` | counter | `generation_relation`, `timing_basis`, `outcome` | Durable native alarm execution evidence; only Android `exact_callback` has an exact callback instant |
| `nolate_departure_alarm_fire_delay_seconds` | histogram/summary | `generation_relation`, `direction` | Absolute trigger-to-execution error for `exact_callback` evidence only |
| `nolate_eta_jobs_total` | counter | `outcome` | Durably committed ETA claim, processing, retry, recovery, failure, and uncertain-delivery transitions |
| `nolate_eta_worker_events_total` | counter | `event` | Bounded non-transition observations such as `processing_exception` |
| `nolate_eta_resolutions_total` | counter | `source`, `quality` | Live, selected-route, or saved fallback resolution |
| `nolate_eta_provider_duration_seconds` | histogram/timer | `outcome` | Live TMAP request latency and stable failure category |
| `nolate_eta_transit_provider_events_total` | counter | `provider`, `outcome` | Bounded ODsay route, Seoul bus/subway, and TAGO call outcomes plus local stale/unverified-source rejection |
| `nolate_eta_transit_provider_duration_seconds` | histogram/timer | `provider`, `outcome` | Logical transit-provider lookup latency; providers are `odsay_route`, `seoul_bus`, `seoul_subway`, or `tago_bus` |
| `nolate_eta_transit_provider_wire_duration_seconds` | histogram/timer | `provider`, `operation`, `outcome` | Physical Seoul/TAGO HTTP calls, including application errors, upstream/local rate limiting, and station lookup amplification |
| `nolate_eta_transit_mapping_unsupported_total` | counter | `namespace` | City-code mappings rejected before a provider call; raw user/provider codes are never tags |
| `nolate_eta_arrival_error_seconds` | histogram/summary | `source`, `direction`, `travel_mode`, `provider`, `prediction_basis`, `algorithm_version` | Absolute ETA error for committed, accuracy-eligible, opt-in arrival observations |
| `nolate_eta_observation_funnel_total` | counter | `stage` | First server-observed `exposed`, `prompt_opened`, and `response_stored` transitions |
| `nolate_eta_observation_eligibility_total` | counter | `reason` | Stored observations by bounded server-owned inclusion/exclusion reason |
| `nolate_eta_on_time_outcomes_total` | counter | `travel_mode`, `provider`, `algorithm_version`, `outcome` | Predicted-versus-actual target-arrival classification, including false-safe predictions |
| `nolate_eta_jobs_due` | gauge | none | Number of ETA jobs whose next check is overdue |
| `nolate_eta_jobs_oldest_delay_seconds` | gauge | none | Age of the oldest overdue ETA job |
| `nolate_push_outbox_events_due` | gauge | none | Number of due outbox events |
| `nolate_push_outbox_oldest_delay_seconds` | gauge | none | Age of the oldest due outbox event |
| `nolate_push_outbox_leases_stale` | gauge | none | Outbox leases older than the processing timeout |
| `nolate_push_deliveries_ambiguous` | gauge | none | `DISPATCHING` deliveries older than the provider call bound |
| `nolate_push_token_leases_expired` | gauge | none | Expired token leases awaiting cleanup |
| `nolate_push_delivery_cohort_provider_success` | gauge | none | All provider-success deliveries in the 14-day rolling cohort after the 10-minute grace |
| `nolate_push_delivery_cohort_ack_eligible` | gauge | none | Cohort successes whose frozen token manifest promised ACK capability v1 |
| `nolate_push_delivery_cohort_client_received` | gauge | none | ACK-eligible cohort deliveries with an authenticated RECEIVED server timestamp |
| `nolate_observability_snapshot_failures_total` | counter | none | Database snapshot sampling failures |
| `nolate_observability_snapshot_last_success_seconds` | gauge | none | Epoch time of the last successful database snapshot |

Backlog gauges never query the database during a scrape. A dedicated daemon sampler updates
in-memory values every 30 seconds by default:

```text
OBSERVABILITY_SNAPSHOT_ENABLED=true
OBSERVABILITY_SNAPSHOT_FIXED_DELAY_MS=30000
OBSERVABILITY_SNAPSHOT_INITIAL_DELAY_MS=30000
OBSERVABILITY_SNAPSHOT_SHUTDOWN_WAIT_MS=5000
OBSERVABILITY_PUSH_DELIVERY_COHORT_WINDOW_DAYS=14
OBSERVABILITY_PUSH_DELIVERY_COHORT_GRACE_MINUTES=10
```

The queries use the existing state/time indexes:

- `schedule_push_job(status, next_check_at)`
- `app_notifications(dispatch_status, next_dispatch_at, id)`
- `app_notifications(dispatch_status, dispatch_locked_at, id)`
- `push_deliveries(status, last_attempted_at)`
- `push_deliveries(status, delivered_at, delivery_ack_capability_version, client_received_at)`
- `push_device_token(dispatch_lease_until, id)`

They are read-only and do not acquire pessimistic locks. If sampling fails, application requests
and provider state transitions continue, the gauges retain their last known values, and only the
snapshot failure counter increases. Metric registry failures are also isolated from provider return
values and durable state transitions. Shutdown invalidates the sampler generation before interrupting
the daemon and waits for the bounded interval, so a JDBC read that completes late cannot overwrite
gauges after restart.

Client ACK and ETA arrival meters describe committed rows, not attempted writes. When these meters
are invoked in a transaction, registration is deferred to the transaction's `afterCommit` callback;
a rollback, deadlock victim, or unique-key failure must not increment them. Non-transactional test
and maintenance callers record immediately. A metric registry failure is isolated from the already
committed business transaction.

The ACK stage timestamp columns in `push_deliveries` are server receipt times. The client-provided
event time remains diagnostic input and must not be used as the delivery-latency clock. ACKs are
authenticated, bound to the current account and device, idempotent per delivery/stage, and may be
replayed by the client's durable retry queue. Therefore duplicate ACK counts are expected during
network recovery and are not additional successful deliveries.

## Push delivery measurement contract

`status = 'SUCCESS'` proves that the provider accepted the request; it does not prove that the
device received or displayed it. Use an aged cohort so a recent provider success still has time to
upload its ACK. The following MySQL query uses one `push_deliveries` row (one device delivery) as
the unit. Bind `:from_utc` and `:to_utc` as UTC instants and keep the grace interval fixed in every
comparison.

The 10-minute grace is only the minimum cohort aging and observation wait; it is **not** an
"ACK arrived within 10 minutes" SLA. The rolling query does not impose
`client_received_at <= delivered_at + 10 minutes`. A durable ACK uploaded later still enters the
numerator in a subsequent snapshot while its delivery remains inside the 14-day window. This is an
eventual-confirmation reliability measure; define a separate bounded-latency cohort before making
any time-to-ACK SLA claim.

```sql
WITH aged_success AS (
    SELECT
        id,
        payload_type,
        delivery_ack_capability_version,
        client_received_at,
        client_presented_at,
        alarm_scheduled_at,
        alarm_fired_at,
        client_actioned_at
    FROM push_deliveries
    WHERE status = 'SUCCESS'
      AND delivered_at >= :from_utc
      AND delivered_at < :to_utc
      AND delivered_at < UTC_TIMESTAMP(6) - INTERVAL 10 MINUTE
)
SELECT
    COUNT(*) AS provider_success_deliveries,
    SUM(delivery_ack_capability_version = 1) AS ack_eligible_deliveries,
    ROUND(100.0 * SUM(delivery_ack_capability_version = 1) / NULLIF(COUNT(*), 0), 2)
        AS ack_eligibility_coverage_percent,
    SUM(delivery_ack_capability_version = 1 AND client_received_at IS NOT NULL)
        AS client_received_deliveries,
    ROUND(
        100.0 * SUM(delivery_ack_capability_version = 1 AND client_received_at IS NOT NULL) /
        NULLIF(SUM(delivery_ack_capability_version = 1), 0),
        2
    )
        AS client_received_percent,
    SUM(delivery_ack_capability_version = 1 AND client_presented_at IS NOT NULL)
        AS observable_presented_callbacks,
    ROUND(
        100.0 * SUM(delivery_ack_capability_version = 1 AND client_presented_at IS NOT NULL) /
        NULLIF(SUM(delivery_ack_capability_version = 1), 0),
        2
    )
        AS observable_presented_callback_percent,
    SUM(delivery_ack_capability_version = 1 AND client_actioned_at IS NOT NULL)
        AS actioned_deliveries,
    ROUND(
        100.0 * SUM(delivery_ack_capability_version = 1 AND client_actioned_at IS NOT NULL) /
        NULLIF(SUM(delivery_ack_capability_version = 1), 0),
        2
    )
        AS actioned_percent
FROM aged_success;
```

Plan-v2 alarm scheduling and fallback must **not** use
`push_deliveries.alarm_scheduled_at` as its reliability denominator. That aggregate ACK belongs to
the legacy single-M0 command and cannot identify M15/M10/M5/M0, token ownership, a later failed
mutation, or a snapshot-origin schedule. The outbox now freezes one
`departure_alarm_presentation_assignments` row for every active token at each eligible boundary.
`NATIVE_ALARM` means a fresh, strong, latest-sequence schedule receipt covered the ordinary
reminder; `VISIBLE_FALLBACK` means the immutable visible manifest retained that reminder.
`semantic_warning_visible` independently records whether a safety/traffic warning also had to stay
visible.

Use the assignment as the expected-channel denominator and join it to occurrence-level fire
evidence or the exact frozen visible delivery. This reports authenticated presentation evidence,
not FCM provider acceptance. For native alarms, the server sees one deduplicated row per reported
fire identity and separates direct callback/alerting evidence from OS-delivery inference. For
visible fallback, the frozen delivery exposes only first-seen presentation evidence. Keep modes
separate as well as publishing the combined result.

```sql
WITH aged_assignment AS (
    SELECT *
    FROM departure_alarm_presentation_assignments
    WHERE trigger_at >= :from_utc
      AND trigger_at < :to_utc
      AND assigned_at < CAST(:as_of_utc AS DATETIME(6))
      AND trigger_at < CAST(:as_of_utc AS DATETIME(6)) - INTERVAL 10 MINUTE
), observed AS (
    SELECT
        a.*,
        CASE WHEN a.presentation_mode = 'NATIVE_ALARM' THEN 1 ELSE 0 END
            AS expected_native_count,
        CASE
            WHEN a.presentation_mode = 'VISIBLE_FALLBACK'
              OR a.semantic_warning_visible = TRUE THEN 1
            ELSE 0
        END AS expected_visible_count,
        (
            SELECT COUNT(*)
            FROM departure_alarm_fire_events f
            WHERE f.member_id = a.member_id
              AND f.schedule_id = a.schedule_id
              AND f.occurrence_id = a.occurrence_id
              AND f.device_fingerprint = a.device_fingerprint
              AND f.source_trigger_at = a.trigger_at
              AND f.scheduled_for = f.source_trigger_at
              AND f.server_recorded_at < CAST(:as_of_utc AS DATETIME(6))
        ) AS native_count,
        (
            SELECT COUNT(*)
            FROM departure_alarm_fire_events f
            WHERE f.member_id = a.member_id
              AND f.schedule_id = a.schedule_id
              AND f.occurrence_id = a.occurrence_id
              AND f.device_fingerprint = a.device_fingerprint
              AND f.source_trigger_at = a.trigger_at
              AND f.scheduled_for = f.source_trigger_at
              AND f.generation = a.alarm_generation
              AND f.timing_basis IN ('EXACT_CALLBACK', 'OBSERVED_ALERTING')
              AND f.server_recorded_at < CAST(:as_of_utc AS DATETIME(6))
        ) AS direct_native_count,
        (
            SELECT COUNT(*)
            FROM departure_alarm_fire_events f
            WHERE f.member_id = a.member_id
              AND f.schedule_id = a.schedule_id
              AND f.occurrence_id = a.occurrence_id
              AND f.device_fingerprint = a.device_fingerprint
              AND f.source_trigger_at = a.trigger_at
              AND f.scheduled_for = f.source_trigger_at
              AND f.timing_basis = 'INFERRED_OS_DELIVERY'
              AND f.server_recorded_at < CAST(:as_of_utc AS DATETIME(6))
        ) AS inferred_native_count,
        (
            SELECT COUNT(*)
            FROM departure_alarm_fire_events f
            WHERE f.member_id = a.member_id
              AND f.schedule_id = a.schedule_id
              AND f.generation = a.alarm_generation
              AND f.occurrence_id = a.occurrence_id
              AND f.device_fingerprint = a.device_fingerprint
              AND f.source_trigger_at = a.trigger_at
              AND f.scheduled_for = f.source_trigger_at
              AND f.server_recorded_at < CAST(:as_of_utc AS DATETIME(6))
        ) AS assigned_generation_native_count,
        (
            SELECT COUNT(*)
            FROM departure_alarm_fire_events f
            WHERE f.member_id = a.member_id
              AND f.schedule_id = a.schedule_id
              AND f.generation <> a.alarm_generation
              AND f.occurrence_id = a.occurrence_id
              AND f.device_fingerprint = a.device_fingerprint
              AND f.source_trigger_at = a.trigger_at
              AND f.scheduled_for = f.source_trigger_at
              AND f.server_recorded_at < CAST(:as_of_utc AS DATETIME(6))
        ) AS stale_generation_native_count,
        (
            SELECT COUNT(*)
            FROM push_deliveries d
            WHERE d.member_id = a.member_id
              AND d.event_key = a.logical_event_key
              AND d.device_token_id = a.device_token_id
              AND d.token_ownership_version = a.token_ownership_version
              AND d.client_presented_at IS NOT NULL
              AND d.client_presented_at < CAST(:as_of_utc AS DATETIME(6))
        ) AS visible_count
    FROM aged_assignment a
)
SELECT
    'ALL' AS cohort,
    COUNT(*) AS assignment_count,
    SUM(direct_native_count = expected_native_count AND visible_count = expected_visible_count)
        AS expected_channel_evidence_observed,
    SUM(direct_native_count + visible_count < expected_native_count + expected_visible_count)
        AS missing_observed,
    SUM(native_count + visible_count > expected_native_count + expected_visible_count)
        AS observable_distinct_native_identity_or_cross_channel_duplicate,
    SUM(
        direct_native_count + visible_count = expected_native_count + expected_visible_count
        AND (
            direct_native_count <> expected_native_count
            OR visible_count <> expected_visible_count
        )
    ) AS wrong_channel_observed,
    SUM(native_count) AS deduplicated_native_identity_count,
    SUM(direct_native_count) AS direct_native_evidence_count,
    SUM(inferred_native_count) AS inferred_native_identity_count,
    SUM(assigned_generation_native_count) AS assigned_generation_native_count,
    SUM(stale_generation_native_count) AS stale_generation_native_count,
    ROUND(
        100.0 * SUM(
            direct_native_count = expected_native_count
            AND visible_count = expected_visible_count
        ) / NULLIF(COUNT(*), 0),
        2
    ) AS expected_channel_evidence_percent
FROM observed
UNION ALL
SELECT
    CONCAT('PLATFORM:', platform) AS cohort,
    COUNT(*) AS assignment_count,
    SUM(direct_native_count = expected_native_count AND visible_count = expected_visible_count)
        AS expected_channel_evidence_observed,
    SUM(direct_native_count + visible_count < expected_native_count + expected_visible_count)
        AS missing_observed,
    SUM(native_count + visible_count > expected_native_count + expected_visible_count)
        AS observable_distinct_native_identity_or_cross_channel_duplicate,
    SUM(
        direct_native_count + visible_count = expected_native_count + expected_visible_count
        AND (
            direct_native_count <> expected_native_count
            OR visible_count <> expected_visible_count
        )
    ) AS wrong_channel_observed,
    SUM(native_count) AS deduplicated_native_identity_count,
    SUM(direct_native_count) AS direct_native_evidence_count,
    SUM(inferred_native_count) AS inferred_native_identity_count,
    SUM(assigned_generation_native_count) AS assigned_generation_native_count,
    SUM(stale_generation_native_count) AS stale_generation_native_count,
    ROUND(
        100.0 * SUM(
            direct_native_count = expected_native_count
            AND visible_count = expected_visible_count
        ) / NULLIF(COUNT(*), 0),
        2
    ) AS expected_channel_evidence_percent
FROM observed
GROUP BY platform
UNION ALL
SELECT
    CONCAT(platform, ':', occurrence_id) AS cohort,
    COUNT(*) AS assignment_count,
    SUM(direct_native_count = expected_native_count AND visible_count = expected_visible_count)
        AS expected_channel_evidence_observed,
    SUM(direct_native_count + visible_count < expected_native_count + expected_visible_count)
        AS missing_observed,
    SUM(native_count + visible_count > expected_native_count + expected_visible_count)
        AS observable_distinct_native_identity_or_cross_channel_duplicate,
    SUM(
        direct_native_count + visible_count = expected_native_count + expected_visible_count
        AND (
            direct_native_count <> expected_native_count
            OR visible_count <> expected_visible_count
        )
    ) AS wrong_channel_observed,
    SUM(native_count) AS deduplicated_native_identity_count,
    SUM(direct_native_count) AS direct_native_evidence_count,
    SUM(inferred_native_count) AS inferred_native_identity_count,
    SUM(assigned_generation_native_count) AS assigned_generation_native_count,
    SUM(stale_generation_native_count) AS stale_generation_native_count,
    ROUND(
        100.0 * SUM(
            direct_native_count = expected_native_count
            AND visible_count = expected_visible_count
        ) / NULLIF(COUNT(*), 0),
        2
    ) AS expected_channel_evidence_percent
FROM observed
GROUP BY platform, occurrence_id
UNION ALL
SELECT
    CONCAT(
        platform, ':', occurrence_id, ':', presentation_mode,
        ':semantic-warning=', IF(semantic_warning_visible, 'true', 'false')
    ) AS cohort,
    COUNT(*) AS assignment_count,
    SUM(direct_native_count = expected_native_count AND visible_count = expected_visible_count)
        AS expected_channel_evidence_observed,
    SUM(direct_native_count + visible_count < expected_native_count + expected_visible_count)
        AS missing_observed,
    SUM(native_count + visible_count > expected_native_count + expected_visible_count)
        AS observable_distinct_native_identity_or_cross_channel_duplicate,
    SUM(
        direct_native_count + visible_count = expected_native_count + expected_visible_count
        AND (
            direct_native_count <> expected_native_count
            OR visible_count <> expected_visible_count
        )
    ) AS wrong_channel_observed,
    SUM(native_count) AS deduplicated_native_identity_count,
    SUM(direct_native_count) AS direct_native_evidence_count,
    SUM(inferred_native_count) AS inferred_native_identity_count,
    SUM(assigned_generation_native_count) AS assigned_generation_native_count,
    SUM(stale_generation_native_count) AS stale_generation_native_count,
    ROUND(
        100.0 * SUM(
            direct_native_count = expected_native_count
            AND visible_count = expected_visible_count
        ) / NULLIF(COUNT(*), 0),
        2
    ) AS expected_channel_evidence_percent
FROM observed
GROUP BY platform, occurrence_id, presentation_mode, semantic_warning_visible
ORDER BY cohort;
```

`native_count` is not a physical callback count. Android and iOS merge repeated journal entries
with the same `(alarmId, generation, scheduledFor)` before upload, and the server enforces one row
per member/device/alarm/generation/occurrence/scheduled time. The count can expose two distinct
identities, such as a current-generation and stale-generation alarm at the same source trigger,
but it cannot expose a repeated physical fire of one identity. The same-identity physical native
duplicate rate is `unmeasured` until the client uploads append-only pre-deduplication fire-attempt
telemetry.

`direct_native_count` admits only `EXACT_CALLBACK` and `OBSERVED_ALERTING` from the assignment's
exact `alarm_generation`. An `INFERRED_OS_DELIVERY` row remains in `native_count` and the inference
diagnostic, but it does not satisfy the 90% expected-channel evidence gate because daemon-store
absence is not an observed alert. A stale-generation-only exact callback therefore yields
`native_count = 1`, `direct_native_count = 0`, `inferred_native_count = 0`, and
`stale_generation_native_count = 1`; it remains `missing_observed`, cannot satisfy the current
assignment, and is not mislabeled as inferred delivery. `inferred_native_count` explicitly counts
only rows whose timing basis is `INFERRED_OS_DELIVERY`; it is not computed by subtracting the
assigned-generation direct count from the all-generation identity count. Distinct
inferred/current/stale identities still contribute to the separate observable
distinct-native-identity or cross-channel duplicate blocker.

`visible_count` cannot measure physical visible-notification duplicates. The database has one
`push_deliveries` row for the frozen event/device ownership and `client_presented_at` records the
first authenticated presentation acknowledgement on that row. Two OS-visible renders of the same
logical event still produce `visible_count = 1`. Consequently,
`expected_channel_evidence_percent` requires expected native-channel evidence to be directly
observed and reports surplus distinct native identities or cross-channel evidence separately, but
it is neither a same-identity native nor a visible-only physical duplicate rate.

For this release, ordinary Android/iOS foreground-local visible-fallback duplicate prevention is a
client invariant, not a measured delivery result. This JavaScript/Expo path excludes the canonical
Android `SCHEDULE_DEPARTURE_REMINDER` shared-native path described below, and it does not cover an
FCM notification payload that the OS presents directly while the app is backgrounded. After
verifying the current account, the ordinary foreground client scopes a canonical claim to
`(recipient account, logicalEventKey)`;
only a legacy message without `logicalEventKey` uses its
provider message ID as the logical identifier. It hashes that canonical
key with SHA-256 to derive a stable Expo notification identifier. The durable protocol is
`PENDING` -> OS schedule ->
`COMMITTED`; an explicit scheduling failure rolls the `PENDING` claim back. Claims are pruned after
seven days and capped at 256 records. A mismatched or unverified account fails closed. If durable
storage fails only after account verification, presentation fails open to avoid silently losing
the alert.

These controls reduce duplicates but cannot guarantee exactly-once presentation. Two crash windows
remain. First, a claim-before-schedule crash leaves `PENDING` and can delay or miss the notification
until a later delivery reclaims its stale lease; that recovery re-requests the same stable OS
identifier. Second, an OS-accepted-before-`COMMITTED` crash leaves `PENDING` even though the OS
accepted the request; stale-lease recovery can make a bounded re-request with the same identifier.
The stable identifier mitigates repeat scheduling, but iOS may still re-alert. The verified-account
storage fail-open path can also duplicate. Keep the focused client state-machine and crash-window
tests as a release blocker. Until an append-only claim-attempt/presentation journal is uploaded,
the actual visible-only duplicate rate is `unmeasured`; do not infer it from this SQL or from a
passing 90% expected-channel evidence score.

An OS-presented ordinary FCM notification received while the app is backgrounded bypasses that
foreground-local claim entirely. All standard visible payloads, including
`SCHEDULE_DEPARTURE_REMINDER`, retain their top-level FCM `Notification` and
`AndroidNotification` for legacy-client auto-display compatibility. The server hashes
`logicalEventKey` with SHA-256 into an opaque, stable, 64-ASCII-character provider replacement
identifier and uses it as
`AndroidNotification.tag`; APNs uses the same value as `apns-collapse-id`. Notification messages
are already collapsible in FCM, which ignores a custom Android collapse key for this message type;
do not treat either `AndroidConfig.collapseKey` or a data field named `collapse_key` as this
guarantee.

`SCHEDULE_DEPARTURE_REMINDER` additionally carries the version-compatible Android action contract.
Its canonical data must contain `type`, `scheduleId`, `recipientMemberId`, `logicalEventKey`, and
`etaEventExpiresAt`, plus `nolateNotificationTitle`, `nolateNotificationBody`, and
`nolateNotificationTag`. The server requires already-trimmed display text with no ASCII control
characters, a 1..100-character title, a 1..500-character body, bounded positive numeric schedule
and recipient identifiers, and an actionable logical key in exactly one of these forms:
`key:` followed by 64 lowercase hexadecimal characters, or `event:` followed by a canonical UUID.
The lowercase 64-ASCII-character transport tag must equal SHA-256 of that logical event key.
Missing or invalid fields fail closed before Firebase is called.

The new Android `FirebaseMessagingService` overrides the notification-intent handling boundary for
this one canonical type before Firebase's base auto-display path. After verifying the current
recipient, expiry, logical identity, display fields, and transport tag, it routes background and
quit-state delivery through the native presentation coordinator. The foreground JavaScript handler
recognizes the same canonical reminder and calls the native `presentDepartureReminder` bridge
instead of the ordinary JavaScript/Expo durable-claim presenter. Both entries use the same
process-wide lifecycle lock, native durable claim store, and `NotificationCompat` presenter for the
notification and depart-now action.

That shared presenter derives the device-visible replacement tag as
`nolate-visible-SHA256("logical\0recipientMemberId\0logicalEventKey")`; it does not use
`nolateNotificationTag` directly as the local notification tag. This recipient-bound derivation
is paired with notification id `0` in every app state. A narrow foreground/background transition
therefore converges on the same stable `(recipient-bound tag, notification id=0)` OS row and the
same native claim rather than scheduling through two independent presenters. Ordinary Android and
iOS foreground visible fallbacks continue to use the JavaScript/Expo durable-claim path above. A
legacy Android client without this handler continues to show the retained FCM notification
automatically, so deploying the server first does not create a background/quit notification gap.
The same reminder keeps its iOS APNs alert,
`schedule_depart_now` category, `apns-collapse-id`, and `apns-expiration`. Other normal visible
pushes and `DEPARTURE_ALARM_SYNC` remain unchanged.

Every standard visible ETA payload containing `etaEventExpiresAt` must cross the provider boundary
with a nonblank `logicalEventKey` and a parseable `Instant` that is still strictly in the future.
The server maps its remaining millisecond duration to Android TTL and its absolute epoch seconds to
`apns-expiration`. A missing logical identity and malformed, expired, sub-millisecond, or
provider-range-invalid expiration fail closed as a confirmed local rejection before Firebase is
called. The stricter reminder-only identity and display-field checks above use the same rejection
path. Because the provider never saw that attempt, the delivery may return safely to `FAILED`; the
authoritative schedule expiry fence and next ETA evaluation then close the stale immutable event
and produce catch-up data instead of sending it late.

Provider or client-side replacement is still not an exactly-once guarantee. Android can update the
drawer entry under the same provider tag or recipient-bound `NotificationCompat` tag yet play sound
or vibration again for a repeated delivery, and neither platform can retract an alert already
presented before a later collapse. Keep the focused provider-payload, Android interception/renderer,
replacement, and expiration tests as release blockers and keep the background visible-only
duplicate rate `unmeasured` without physical presentation-attempt telemetry.

The all-generation `native_count` is deliberately a count of deduplicated identities, not
`EXISTS`. A visible fallback can coincide with a reported alarm identity from an older generation,
and current plus stale native identities can both survive deduplication. Collapsing those rows to a
boolean would hide the observable distinct-identity surplus. It still says nothing about repeated
physical execution of one identity. An initial alarm has
`scheduled_for = source_trigger_at`; this excludes a user-requested snooze from the initial
presentation count. Publish snoozes separately.

`semantic_warning_visible = TRUE` means traffic degradation, an earlier departure, transfer
failure, catch-up information, or an impossible-on-time warning had to remain visible even though
the ordinary boundary reminder was covered by a native alarm. Such a `NATIVE_ALARM` assignment
therefore expects one native presentation **and** one visible warning; it is not classified as an
accidental duplicate. A `VISIBLE_FALLBACK` expects no native presentation and one visible
presentation. The release result requires the expected count in each channel, so one native plus
one native cannot substitute for the expected native-plus-visible pair.

The assignment freezes the token platform at the same lock boundary, so Android and iOS scores
cannot mask one another through a later token update or a combined average.

Treat any `UNKNOWN` platform row in a post-v2 cohort as a measurement defect; do not redistribute
it into Android or iOS after the fact.

Use one immutable `:as_of_utc` for the assignment-age cutoff and every server-recorded observation
cutoff. Re-running the same `:from_utc`, `:to_utc`, and `:as_of_utc` then cannot improve merely
because a late fire journal or visible-presentation acknowledgement arrived after report close.
Account and schedule privacy cleanup can legitimately remove raw assignments and evidence later,
so persist the first report's numerator, denominator, slices, and query revision in an append-only
operational snapshot; `:as_of_utc` freezes late observations, not later privacy deletion.

The canonical result uses the exact assigned trigger. During a live ETA plan change, an older
generation can retain the same occurrence at a different nearby trigger, which an exact join
cannot attribute safely. Run this orphan diagnostic beside every report (20 minutes matches the
maximum supported reminder horizon here). It excludes a fire that has its own exact immutable
assignment, so two valid old/new plan assignments do not mark one another stale. Any remaining
row makes the canonical percentage incomplete until the fire is attributed or reconciled:

```sql
WITH aged_assignment AS (
    SELECT *
    FROM departure_alarm_presentation_assignments
    WHERE trigger_at >= :from_utc
      AND trigger_at < :to_utc
      AND assigned_at < CAST(:as_of_utc AS DATETIME(6))
      AND trigger_at < CAST(:as_of_utc AS DATETIME(6)) - INTERVAL 10 MINUTE
)
SELECT
    a.platform,
    a.occurrence_id,
    a.presentation_mode,
    a.semantic_warning_visible,
    COUNT(*) AS orphan_nearby_mismatched_trigger_native_count
FROM aged_assignment a
JOIN departure_alarm_fire_events f
  ON f.member_id = a.member_id
 AND f.schedule_id = a.schedule_id
 AND f.occurrence_id = a.occurrence_id
 AND f.device_fingerprint = a.device_fingerprint
 AND f.scheduled_for = f.source_trigger_at
 AND f.server_recorded_at < CAST(:as_of_utc AS DATETIME(6))
 AND (a.alarm_generation IS NULL OR f.generation <> a.alarm_generation)
 AND f.source_trigger_at <> a.trigger_at
 AND f.source_trigger_at BETWEEN
        a.trigger_at - INTERVAL 20 MINUTE AND a.trigger_at + INTERVAL 20 MINUTE
WHERE NOT EXISTS (
    SELECT 1
    FROM departure_alarm_presentation_assignments matched
    WHERE matched.member_id = f.member_id
      AND matched.schedule_id = f.schedule_id
      AND matched.alarm_generation = f.generation
      AND matched.occurrence_id = f.occurrence_id
      AND matched.device_fingerprint = f.device_fingerprint
      AND matched.trigger_at = f.source_trigger_at
      AND matched.assigned_at < CAST(:as_of_utc AS DATETIME(6))
)
GROUP BY a.platform, a.occurrence_id, a.presentation_mode, a.semantic_warning_visible
ORDER BY a.platform, a.occurrence_id, a.presentation_mode, a.semantic_warning_visible;
```

Assignment rows exist only after an eligible worker boundary reaches the outbox transaction. A
missed worker boundary would otherwise disappear from the denominator and inflate reliability.
Run the following short-window auditor at least once per minute while the v2 state still exists.
It expands the current plan and current active ownerships, then checks that the boundary produced
an immutable assignment. Because token ownership and desired state are mutable, retain the query
result externally; this is an operational coverage check, not a permanent historical denominator.

```sql
WITH live_expected AS (
    SELECT
        s.member_id,
        s.schedule_id,
        s.generation,
        jt.occurrence_id,
        CAST(REPLACE(REPLACE(jt.trigger_at, 'T', ' '), 'Z', '') AS DATETIME(6)) AS trigger_at,
        t.id AS device_token_id,
        t.ownership_version AS token_ownership_version
    FROM departure_alarm_sync_state s
    JOIN JSON_TABLE(
        s.alarm_occurrences_json,
        '$[*]' COLUMNS (
            occurrence_id VARCHAR(16) PATH '$.occurrenceId',
            trigger_at VARCHAR(40) PATH '$.triggerAt'
        )
    ) jt
    JOIN push_device_token t
      ON t.member_id = s.member_id
     AND t.retirement_requested = FALSE
    WHERE s.operation = 'UPSERT'
      AND s.alarm_plan_schema_version = '2'
), due AS (
    SELECT *
    FROM live_expected
    WHERE trigger_at >= UTC_TIMESTAMP(6) - INTERVAL 8 MINUTE
      AND trigger_at < UTC_TIMESTAMP(6) - INTERVAL 2 MINUTE
)
SELECT
    COUNT(*) AS expected_boundary_ownerships,
    SUM(EXISTS (
        SELECT 1
        FROM departure_alarm_presentation_assignments a
        WHERE a.member_id = due.member_id
          AND a.schedule_id = due.schedule_id
          AND a.alarm_generation = due.generation
          AND a.occurrence_id = due.occurrence_id
          AND a.trigger_at = due.trigger_at
          AND a.device_token_id = due.device_token_id
          AND a.token_ownership_version = due.token_ownership_version
    )) AS assigned_boundary_ownerships,
    ROUND(
        100.0 * SUM(EXISTS (
            SELECT 1
            FROM departure_alarm_presentation_assignments a
            WHERE a.member_id = due.member_id
              AND a.schedule_id = due.schedule_id
              AND a.alarm_generation = due.generation
              AND a.occurrence_id = due.occurrence_id
              AND a.trigger_at = due.trigger_at
              AND a.device_token_id = due.device_token_id
              AND a.token_ownership_version = due.token_ownership_version
        )) / NULLIF(COUNT(*), 0),
        2
    ) AS boundary_assignment_coverage_percent
FROM due;
```

Do not publish the expected-channel evidence percentage when this auditor is below 99%, when the due-job
gauge is nonzero for more than two consecutive samples, when the auditor has no expected rows, or
when the nearby mismatched-trigger diagnostic is nonzero and unresolved. A
future immutable occurrence-expectation table is required before this coverage can be recomputed
historically; `schedule_push_job` keeps only mutable last-boundary fields and is not a valid
historical denominator.

Android records the exact alarm `BroadcastReceiver` callback before foreground-service startup;
iOS time-sensitive delivery is reconciled from the OS delivered-notification list into a bounded
native journal before JavaScript runs. Cold
start, fresh login, and foreground activation replay that journal. Push-origin alarms write both
`push_deliveries.alarm_fired_at` and a provider-independent
`departure_alarm_fire_events` row; snapshot-origin alarms write the latter even though they have
no logical push event. Both paths are authenticated and idempotent. The raw installation id is
fingerprinted before persistence, while client execution time and authoritative server receipt
time remain separate. AlarmKit's `.alerting` reconciliation is stored as `OBSERVED_ALERTING`.
When a persisted one-shot AlarmKit alarm is absent from the daemon store only after its trigger,
Apple's persistence contract permits it to be recorded as `INFERRED_OS_DELIVERY`. Both iOS bases
count in the separate execution-coverage diagnostic, but inference does not satisfy the strict 90%
expected-channel evidence gate. Only `OBSERVED_ALERTING` is direct iOS presentation evidence, and
neither iOS basis enters the exact-delay histogram.

Snapshot and push command applications are persisted as device-bound receipts. This makes the aged
scheduled cohort an explicit denominator. Deduplicate replays by the server-generated command
receipt key and report delivery mode separately. Android exact callbacks, iOS observed alerting,
and iOS inferred one-shot delivery must remain separate timing-basis cohorts.

Coverage accepts a receipt only while both its client occurrence time and authoritative server
receipt time are within the configured 24-hour TTL. The server requests revalidation every 12
hours by increasing `alarmValidationRevision` without changing the desired `alarmGeneration` or
plan. Losing that control push therefore does not immediately invalidate an otherwise fresh
receipt; when the receipt finally ages out, the boundary fails open to a visible fallback. A fresh
higher `mutation_sequence` receipt renews coverage, while a fresh failure makes that ownership
visible immediately. Revalidation is skipped when the next occurrence is within the 30-minute
delivery safety lead.

Permission revocation and OS alarm removal have no synchronous server signal. It is impossible to
guarantee both zero missed alerts and zero duplicates in that uncertainty window: fail-open
fallback avoids a silent miss, while an old native alarm that the OS retained can still create a
duplicate. The any-generation assignment query above measures the native and cross-channel portion
of this tradeoff instead of hiding it; visible-only duplicates remain unmeasured.

```sql
WITH aged_native_assignment AS (
    SELECT *
    FROM departure_alarm_presentation_assignments
    WHERE presentation_mode = 'NATIVE_ALARM'
      AND trigger_at >= :from_utc
      AND trigger_at < :to_utc
      AND assigned_at < CAST(:as_of_utc AS DATETIME(6))
      AND trigger_at < CAST(:as_of_utc AS DATETIME(6)) - INTERVAL 10 MINUTE
), receipt_at_assignment AS (
    SELECT
        a.*,
        r.outcome AS receipt_outcome,
        r.platform AS receipt_platform,
        r.delivery_mode,
        r.source AS receipt_source,
        ROW_NUMBER() OVER (
            PARTITION BY a.id
            ORDER BY
                r.mutation_sequence DESC,
                CASE WHEN r.outcome = 'SCHEDULED' THEN 1 ELSE 0 END ASC,
                r.client_occurred_at DESC,
                r.server_recorded_at DESC,
                r.id DESC
        ) AS receipt_rank
    FROM aged_native_assignment a
    LEFT JOIN departure_alarm_schedule_receipts r
      ON r.member_id = a.member_id
     AND r.schedule_id = a.schedule_id
     AND r.generation = a.alarm_generation
     AND r.occurrence_id = a.occurrence_id
     AND r.trigger_at = a.trigger_at
     AND r.device_token_id = a.device_token_id
     AND r.token_ownership_version = a.token_ownership_version
     AND r.device_fingerprint = a.device_fingerprint
     AND r.mutation_sequence IS NOT NULL
     AND r.server_recorded_at <= a.assigned_at
), cohort AS (
    SELECT *
    FROM receipt_at_assignment
    WHERE receipt_rank = 1
)
SELECT
    m.platform AS assigned_platform,
    COALESCE(m.receipt_platform, 'MISSING') AS receipt_platform,
    COALESCE(m.delivery_mode, 'UNKNOWN') AS delivery_mode,
    COALESCE(m.receipt_source, 'MISSING') AS receipt_source,
    m.occurrence_id,
    COUNT(*) AS expected_fires,
    SUM(m.receipt_outcome = 'SCHEDULED') AS frozen_schedule_receipt_invariant,
    SUM(EXISTS (
        SELECT 1
        FROM departure_alarm_fire_events f
        WHERE f.member_id = m.member_id
          AND f.device_fingerprint = m.device_fingerprint
          AND f.schedule_id = m.schedule_id
          AND f.generation = m.alarm_generation
          AND f.occurrence_id = m.occurrence_id
          AND f.source_trigger_at = m.trigger_at
          AND f.scheduled_for = f.source_trigger_at
          AND f.server_recorded_at < :as_of_utc
    )) AS observed_fires,
    SUM(EXISTS (
        SELECT 1
        FROM departure_alarm_fire_events f
        WHERE f.member_id = m.member_id
          AND f.device_fingerprint = m.device_fingerprint
          AND f.schedule_id = m.schedule_id
          AND f.generation = m.alarm_generation
          AND f.occurrence_id = m.occurrence_id
          AND f.timing_basis = 'EXACT_CALLBACK'
          AND f.source_trigger_at = m.trigger_at
          AND f.scheduled_for = f.source_trigger_at
          AND f.server_recorded_at < :as_of_utc
    )) AS exact_callback_fires,
    SUM(EXISTS (
        SELECT 1
        FROM departure_alarm_fire_events f
        WHERE f.member_id = m.member_id
          AND f.device_fingerprint = m.device_fingerprint
          AND f.schedule_id = m.schedule_id
          AND f.generation = m.alarm_generation
          AND f.occurrence_id = m.occurrence_id
          AND f.timing_basis = 'OBSERVED_ALERTING'
          AND f.source_trigger_at = m.trigger_at
          AND f.scheduled_for = f.source_trigger_at
          AND f.server_recorded_at < :as_of_utc
    )) AS observed_alerting_fires,
    SUM(EXISTS (
        SELECT 1
        FROM departure_alarm_fire_events f
        WHERE f.member_id = m.member_id
          AND f.device_fingerprint = m.device_fingerprint
          AND f.schedule_id = m.schedule_id
          AND f.generation = m.alarm_generation
          AND f.occurrence_id = m.occurrence_id
          AND f.timing_basis = 'INFERRED_OS_DELIVERY'
          AND f.source_trigger_at = m.trigger_at
          AND f.scheduled_for = f.source_trigger_at
          AND f.server_recorded_at < :as_of_utc
    )) AS inferred_os_delivery_fires,
    ROUND(100.0 * SUM(EXISTS (
        SELECT 1
        FROM departure_alarm_fire_events f
        WHERE f.member_id = m.member_id
          AND f.device_fingerprint = m.device_fingerprint
          AND f.schedule_id = m.schedule_id
          AND f.generation = m.alarm_generation
          AND f.occurrence_id = m.occurrence_id
          AND f.source_trigger_at = m.trigger_at
          AND f.scheduled_for = f.source_trigger_at
          AND f.server_recorded_at < :as_of_utc
    )) / NULLIF(COUNT(*), 0), 2) AS observed_fire_percent
FROM cohort m
GROUP BY
    m.platform, m.receipt_platform, m.delivery_mode, m.receipt_source, m.occurrence_id;
```

This is a diagnostic within the canonical frozen `NATIVE_ALARM` assignment denominator, not a
second reliability denominator. Receipt ranking is evaluated as of `assigned_at`, before later
capability changes, so the report window is defined by `trigger_at` and cannot omit an earlier
valid receipt or admit a later stale success. The highest mutation sequence wins for each exact
assignment and a non-scheduled result wins a same-sequence tie. The schedule-receipt invariant must
equal `expected_fires`; a difference is an assignment transaction defect, not a missing fire.

Treat `IOS_ALARM_KIT` as measured execution coverage when either `observed_alerting_fires` or
`inferred_os_delivery_fires` exists. Its exact timing remains unmeasured; only Android
`exact_callback_fires` belongs in the delay cohort. Inspect coverage, exact timing, and
stale-generation defects independently.

```sql
WITH ranked AS (
    SELECT
        generation_relation,
        ABS(fire_delay_seconds) AS absolute_delay_seconds,
        CUME_DIST() OVER (
            PARTITION BY generation_relation
            ORDER BY ABS(fire_delay_seconds)
        ) AS cumulative_fraction
    FROM departure_alarm_fire_events
    WHERE server_recorded_at >= :from_utc
      AND server_recorded_at < :to_utc
      AND timing_basis = 'EXACT_CALLBACK'
)
SELECT
    generation_relation,
    COUNT(*) AS observed_fires,
    ROUND(AVG(absolute_delay_seconds), 1) AS mean_absolute_delay_seconds,
    MIN(CASE WHEN cumulative_fraction >= 0.90 THEN absolute_delay_seconds END)
        AS p90_absolute_delay_seconds
FROM ranked
GROUP BY generation_relation;
```

The app still cannot observe every notification the OS presents while JavaScript is suspended.
`client_presented_at` measures only observable callbacks, not complete notification-tray display.
Native execution also still needs a qualifying field cohort: simulator/native unit tests prove the
bridge contract, not OS delivery reliability on production devices.

## ETA accuracy and coverage contract

An ETA observation is explicit, authenticated, and opt-in. The immutable departure snapshot keeps
the evaluated time, target arrival, recommended departure, predicted arrival, source, freshness,
travel mode, provider, algorithm version, provider fetch time, prediction basis, and predicted
on-time classification even after the ETA job is cancelled. The client arrival time and the
server recording time are stored separately. Plausibility validation rejects arrival before
departure, excessive future clock skew, and reports older than the configured reporting window.
For `USER_ADJUSTED`, report delay is measured from the reconstructed client capture time
(`actual_arrival_at + adjustment_seconds`), so the correction bucket is not counted as upload lag.
Each row also keeps the bounded observation source, temporal precision, and (for a user-adjusted
timestamp only) the whole-minute correction. These fields describe time uncertainty; they are not
GPS accuracy and must not be inferred from server receipt latency.

Only `accuracy_eligible = 1` rows belong in accuracy aggregates. Ineligible rows remain available
for diagnosis. The public arrival API always writes `UNVERIFIED_CLIENT`: `USER_NOW`,
`USER_ADJUSTED`, and a client-claimed `GEOFENCE` are therefore all ineligible, regardless of a
client-supplied precision value. In particular, `USER_NOW + 30 seconds` describes tap-time
uncertainty and is not verified arrival ground truth. Verification is server-owned and absent from
the public request contract.

A future `VERIFIED_GEOFENCE` producer must be separately reviewed, explicitly consented, and must
not be inferred from a client label. It must also supply bounded app/build cohorts, a non-
`unversioned` backend cohort, and a known algorithm version; otherwise eligibility fails closed.
Arrival verification does not verify the user's depart-now tap. Until an independently reviewed,
server-owned departure producer exists, `DEPARTURE_ANCHORED_DURATION` predictions (including TMAP
road ETA) remain ineligible with `UNVERIFIED_DEPARTURE`; only `PROVIDER_ABSOLUTE` predictions can
enter the future verified-arrival cohort.
No such producer or background location tracking is implemented today. Consequently production
ETA accuracy remains **unmeasured** until that follow-up exists and a qualifying field cohort
matures.
The durable database is the authority for offline analysis; Prometheus is useful for trend alerts
but cannot replace cohort reconstruction.

Use the following MySQL 8 query for eligible sample count, MAE, and P90 absolute error. Never pool
travel modes, providers, or prediction bases merely to make a sparse slice look statistically
healthy.

```sql
WITH eligible AS (
    SELECT
        travel_mode,
        provider_id,
        algorithm_version,
        prediction_basis,
        eta_source,
        observation_source,
        precision_seconds,
        absolute_error_seconds,
        predicted_on_time,
        on_time_outcome
    FROM schedule_eta_accuracy_observations
    WHERE accuracy_eligible = 1
      AND recorded_at >= :from_utc
      AND recorded_at < :to_utc
), ranked AS (
    SELECT
        travel_mode,
        provider_id,
        algorithm_version,
        prediction_basis,
        eta_source,
        observation_source,
        precision_seconds,
        absolute_error_seconds,
        predicted_on_time,
        on_time_outcome,
        CUME_DIST() OVER (
            PARTITION BY
                travel_mode, provider_id, algorithm_version, prediction_basis, eta_source,
                observation_source, precision_seconds
            ORDER BY absolute_error_seconds
        ) AS cumulative_fraction
    FROM eligible
)
SELECT
    travel_mode,
    provider_id,
    algorithm_version,
    prediction_basis,
    eta_source,
    observation_source,
    precision_seconds,
    COUNT(*) AS eligible_samples,
    ROUND(AVG(absolute_error_seconds), 1) AS mae_seconds,
    MIN(CASE WHEN cumulative_fraction >= 0.90 THEN absolute_error_seconds END)
        AS p90_absolute_error_seconds,
    SUM(predicted_on_time) AS predicted_on_time_samples,
    SUM(on_time_outcome = 'PREDICTED_ON_TIME_ACTUAL_LATE') AS false_safe_samples,
    ROUND(
        100.0 * SUM(on_time_outcome = 'PREDICTED_ON_TIME_ACTUAL_LATE') /
        NULLIF(SUM(predicted_on_time), 0),
        2
    ) AS false_safe_percent
FROM ranked
GROUP BY travel_mode, provider_id, algorithm_version, prediction_basis, eta_source,
    observation_source, precision_seconds
ORDER BY travel_mode, provider_id, algorithm_version, prediction_basis, eta_source,
    observation_source, precision_seconds;
```

Coverage has two separate denominators. Snapshot coverage asks whether a completed departure had
all prediction provenance needed for later evaluation. Opt-in observation coverage asks how many
otherwise eligible frozen predictions received arrival ground truth. The latter query below
reproduces the default eligibility thresholds (60-minute prediction age and 15-minute departure
offset for provider-absolute predictions); update it together with the corresponding application
configuration.

```sql
WITH departed AS (
    SELECT *
    FROM schedule_departure_statuses
    WHERE departed_at >= :from_utc
      AND departed_at < :to_utc
), snapshot_coverage AS (
    SELECT
        COUNT(*) AS departed_count,
        SUM(
            eta_snapshot_evaluated_at IS NOT NULL
            AND eta_snapshot_recommended_departure_at IS NOT NULL
            AND eta_snapshot_predicted_arrival_at IS NOT NULL
            AND eta_snapshot_source IS NOT NULL
            AND eta_snapshot_stale IS NOT NULL
            AND eta_snapshot_travel_minutes IS NOT NULL
            AND eta_snapshot_prediction_basis IS NOT NULL
            AND eta_snapshot_travel_mode IS NOT NULL
            AND eta_snapshot_provider_id IS NOT NULL
            AND eta_snapshot_target_arrival_at IS NOT NULL
            AND eta_snapshot_on_time_arrival_possible IS NOT NULL
            AND eta_snapshot_algorithm_version IS NOT NULL
        ) AS complete_snapshot_count
    FROM departed
), eligible_departures AS (
    SELECT d.schedule_id, d.member_id
    FROM departed d
    WHERE d.eta_snapshot_stale = 0
      AND d.eta_snapshot_source IN ('LIVE_PROVIDER', 'TIMETABLE_PROVIDER')
      AND d.eta_snapshot_provider_id IN ('ODSAY_TRANSIT', 'TMAP')
      AND d.eta_snapshot_target_arrival_at IS NOT NULL
      AND d.eta_snapshot_on_time_arrival_possible IS NOT NULL
      AND d.eta_snapshot_algorithm_version IS NOT NULL
      AND d.eta_snapshot_provider_fetched_at IS NOT NULL
      AND d.eta_snapshot_prediction_basis = 'PROVIDER_ABSOLUTE'
      AND d.eta_snapshot_travel_mode IS NOT NULL
      AND d.eta_snapshot_recommended_departure_at IS NOT NULL
      AND d.eta_snapshot_predicted_arrival_at IS NOT NULL
      AND d.eta_snapshot_travel_minutes > 0
      AND d.eta_snapshot_evaluated_at <= d.departed_at
      AND d.eta_snapshot_evaluated_at >= d.departed_at - INTERVAL 60 MINUTE
      AND d.eta_snapshot_provider_fetched_at <= d.departed_at
      AND d.eta_snapshot_provider_fetched_at >= d.departed_at - INTERVAL 60 MINUTE
      AND ABS(TIMESTAMPDIFF(
          SECOND,
          d.eta_snapshot_recommended_departure_at,
          d.departed_at
      )) <= 15 * 60
), observation_coverage AS (
    SELECT
        COUNT(*) AS eligible_departure_count,
        SUM(o.id IS NOT NULL) AS arrival_observation_count,
        SUM(o.accuracy_eligible = 1) AS eligible_observation_count
    FROM eligible_departures d
    LEFT JOIN schedule_eta_accuracy_observations o
      ON o.schedule_id = d.schedule_id
     AND o.member_id = d.member_id
)
SELECT
    s.departed_count,
    s.complete_snapshot_count,
    ROUND(100.0 * s.complete_snapshot_count / NULLIF(s.departed_count, 0), 2)
        AS snapshot_coverage_percent,
    o.eligible_departure_count,
    o.arrival_observation_count,
    o.eligible_observation_count,
    ROUND(100.0 * o.arrival_observation_count /
        NULLIF(o.eligible_departure_count, 0), 2) AS opt_in_observation_coverage_percent,
    ROUND(100.0 * o.eligible_observation_count /
        NULLIF(o.eligible_departure_count, 0), 2) AS eligible_sample_yield_percent
FROM snapshot_coverage s
CROSS JOIN observation_coverage o;
```

For product-funnel coverage, use the same closed `departed_at` cohort and count the first-event
columns on `schedule_departure_statuses`: exposed/departed, prompted/exposed, and
responded/exposed. A missing exposure on a legacy client is unknown coverage, not a negative
response. Keep this funnel separate from `accuracy_eligible`; a self-report response improves the
response denominator but never becomes verified ground truth.

The client persists `EXPOSED` and `PROMPT_OPENED` before sending, replays them idempotently for at
most 24 hours, and records `EXPOSED` only after at least half of the action card is measured inside
the active viewport. The server freezes bounded app version, build version, and UX variant
independently at each first transition. Slice non-response denominators with the corresponding
`eta_observation_exposed_*` fields; use `eta_observation_prompted_*` only for the post-prompt
response funnel. Null cohort fields are legacy/unknown and must not be silently assigned to a new
release. The local arrival replay queue stores no title, route, or coordinates, stops considering
its required exact arrival timestamp at 24 hours, and physically removes it on the next active
queue pass (or immediately during logout/account deletion). A terminated OS process cannot run a
wall-clock deletion callback, so device-at-rest verification must account for that next-launch
boundary.

```sql
SELECT
    eta_observation_exposed_client_app_version AS app_version,
    eta_observation_exposed_client_build_version AS build_version,
    eta_observation_exposed_ux_variant AS ux_variant,
    COUNT(*) AS exposed,
    SUM(eta_observation_prompted_at IS NOT NULL) AS prompted,
    SUM(eta_observation_responded_at IS NOT NULL) AS responded,
    SUM(eta_observation_responded_at IS NULL) AS exposed_without_response
FROM schedule_departure_statuses
WHERE departed_at >= :from_utc
  AND departed_at < :to_utc
  AND eta_observation_exposed_at IS NOT NULL
GROUP BY
    eta_observation_exposed_client_app_version,
    eta_observation_exposed_client_build_version,
    eta_observation_exposed_ux_variant;
```

For prompt-to-response conversion, repeat the slice with the prompted cohort fields and require
`eta_observation_prompted_at IS NOT NULL`. Do not mix exposed-release and prompted-release fields
when a user upgraded between the two transitions.

### Minimum sample and reporting rules

- Define cohorts by `departed_at`, not report receipt time, and wait at least the configured
  24-hour report window after the cohort end before closing it. Use a stable client app/build,
  backend, eligibility-policy, and algorithm cohort. A first field report should span at least 14
  complete days.
- Publish MAE/P90 and false-safe rate for a mode/provider/algorithm-version/prediction-basis/ETA
  source/observation-source/precision slice only when it has at least 200
  eligible observations. Below that threshold, publish the count and `insufficient sample`; do not
  publish a reliability score for the slice.
- Always publish snapshot coverage, opt-in observation coverage, and eligible sample yield beside
  accuracy. Opt-in coverage is not ETA accuracy and must not be presented as if non-reporting users
  had the same error distribution.
- Preserve exact numerator, denominator, grace interval, exclusion count, application version, and
  query revision with each report. Do not compare windows that changed any of these definitions.

Initial review targets may be proposed as `client_received >= 97%` of aged, ACK-capability-v1
provider successes,
`boundary_assignment_coverage >= 99%` in the live boundary auditor,
and `expected_channel_evidence_percent >= 90%` for the aged per-ownership assignment cohort. This
requires `EXACT_CALLBACK` or `OBSERVED_ALERTING` for an assigned native channel, first-seen
presentation evidence for an assigned visible channel, and both expected channels for rows whose
semantic warning flag is true. `INFERRED_OS_DELIVERY` is diagnostic execution coverage and never a
passing native observation. The score asserts neither a same-identity native nor a visible-only
physical duplicate rate. Publish the combined score only with at least 500 assignments, and
publish every platform, platform/occurrence, and
platform/occurrence/presentation-mode/semantic-warning slice only with at least 100; otherwise
label that slice `insufficient sample`. The combined cohort and every sufficiently sampled slice
must each be at least 90%; Android success cannot offset an iOS failure, and strong M15 results
cannot hide an M0 or fallback defect. Require M15/M10/M5/M0 platform/occurrence slices explicitly
once each reaches 100 assignments. A slice below 100 assignments is `insufficient sample` and
follows the separately approved staged rollout policy; it must not be silently merged into another
slice and called passing. Any missing boundary audit, an assignment-coverage result below 99%, a
persistently overdue job, or an unresolved nearby mismatched-trigger fire makes the
expected-channel evidence score `unmeasured`, even if its sampled percentage is high. Missing,
observable distinct-native-identity/cross-channel duplicate, and wrong-channel counts remain
separate release blockers. Inferred-native counts must accompany the score but cannot repair a
missing direct observation. The ordinary foreground JavaScript/Expo durable-claim prevention tests
and the canonical Android shared-native app-state claim/interception/renderer replacement and
expiration tests are separate release invariants. Without
append-only pre-deduplication native-fire and physical visible presentation-attempt telemetry,
report both same-identity native and visible-only duplicate rates as `unmeasured`. For each
sufficiently sampled ETA slice,
`MAE <= 300 seconds`,
`P90 absolute error <= 600 seconds`, and
`false-safe <= 5%` of predicted-on-time samples. These are candidate
targets requiring product/operations approval, **not observed production results**. Until the
queries above have run on a qualifying field cohort, report observed push delivery and ETA
accuracy as `unmeasured`; passing unit, integration, or deterministic simulation tests is release
evidence for implementation correctness, not a field reliability percentage.

For the rolling gauges, calculate ACK eligibility coverage as `ack_eligible / provider_success` and
last-mile reliability as `client_received / ack_eligible`. A zero total or zero ACK-eligible
denominator is `unmeasured`, never 0%. The measurement-coverage alert requires at least 20 aged
provider successes and fires only when fewer than 90% declared ACK capability v1. Independently,
the last-mile reliability alert requires at least 20 ACK-eligible deliveries and fires only when
fewer than 90% have a RECEIVED acknowledgement. Legacy/null capability rows remain in measurement
coverage but never count as missing RECEIVED acknowledgements in the reliability denominator.

## Staging verification

1. Keep the scrape flag unset. Confirm both anonymous and member-authenticated requests cannot read
   `/actuator/prometheus`.
2. Restrict the path at the ingress or private network.
3. Set the exact opt-in flag and restart one staging instance.
4. Confirm the custom health probes still return their opaque `UP` contract.
5. Scrape and check that only the bounded tags above appear.
6. Run a controlled provider success, confirmed rejection, timeout/unknown outcome, outbox retry,
   stale-lease recovery, and ETA fallback. Compare the counters with durable rows and sanitized
   logs.
7. Validate the draft rules before loading them:

   ```bash
   promtool check rules ops/prometheus/nolate-release-alerts.yml
   promtool test rules ops/prometheus/nolate-release-alerts.test.yml
   ```

8. Give every NoLate scrape target the environment-local Prometheus target label `job="nolate"`.
   The draft freshness rule uses this label to fail closed when the custom snapshot series is
   absent or the scrape target is down. Keep each rule evaluator scoped to one environment, or add
   that environment's label to both the `up` and application-metric selectors.
9. Tune thresholds from staging baseline, including the 90-second freshness bound when changing
   the default 30-second sampler delay. Load the rules into the actual Prometheus-compatible
   system, attach notification routing, and capture links/screenshots as external release evidence.
   Load them per environment, or add the monitoring system's environment/cluster labels to every
   selector before using a shared multi-environment rule evaluator.

Recommended dashboard panels are ETA/outbox due count and oldest delay, stale/expired leases,
ambiguous deliveries, ACK measurement coverage and client-confirmed reliability, push outcome rate
and latency, live ETA provider failure rate and latency,
transit ETA failure/stale/unverified-source rejection rate and latency by bounded provider, native alarm fire
delay/stale-generation count, ETA false-safe rate by algorithm version, and fallback quality ratio.

`NoLateTransitEtaProviderFailureRate` uses the latency timer count as the logical-lookup
denominator. This includes local circuit/bulkhead rejection as a failed lookup; it is not an HTTP
wire-call count. A response fetched successfully and then rejected for an old `sourceUpdatedAt`
does not become two denominator lookups. A response without a provider-owned source timestamp is
reported separately as `rejected_unverified_source`; do not merge it into `rejected_stale`, because
the former requires a provider contract or parser fix while the latter usually indicates latency or
upstream freshness degradation. The numerator includes `timeout`, `http_error`, `invalid`, `empty`,
`rejected_stale`, and `rejected_unverified_source`; it is evaluated separately for each bounded provider and stays silent until that
provider has at least 20 calls in ten minutes. Keep the existing TMAP alert separate because its
provider and outcome contract predates the transit-specific meters.

The draft backlog alerts first discard per-instance snapshots older than 90 seconds and then take
the cluster maximum. This produces one cluster-level alert instead of one duplicate alert per
application instance. Snapshot-failure and ETA processing-event alerts are summed across instances.
The snapshot-stale alert also aggregates to one alert and fails closed when the snapshot series is
absent, all observed `job="nolate"` targets are down, or the scoped `up` series is itself absent.
One healthy target plus a fresh snapshot keeps the cluster alert clear. Load rules separately per
environment, as noted above, so that aggregation never crosses environments.

## Incident response

- Delayed ETA/outbox: verify scheduler enablement, instance count, database latency, and due-row
  index plans before increasing batch size.
- Stale lease: compare configured provider timeout, token lease, and outbox processing timeout.
  Preserve the at-most-once boundary; do not bulk-reset `DISPATCHING` deliveries.
- Ambiguous delivery: correlate the durable delivery row with provider-side evidence. Do not
  automatically resend when acceptance cannot be proven.
- Provider failure rate: separate confirmed rejection from `unknown`; an unknown transport outcome
  remains non-retryable unless operator evidence proves non-acceptance.
- Snapshot failure: treat gauges as stale, inspect database connectivity separately, and use the
  existing SQL rollout checks until sampling recovers.
- ETA processing exception: use the event counter for execution failures and the ETA job outcome
  counter for the independently committed retry or terminal transition. Do not add the two as if
  they were the same unit.

Crash reporting and alert delivery remain external release work. Connecting a selected SDK/account,
uploading native symbols, defining retention/access, provisioning the scrape target, importing the
dashboard, and testing the paging route must be completed and evidenced in the deployment
environment.
