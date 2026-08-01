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

Measure alarm scheduling only against provider-success deliveries whose frozen payload is the
alarm-control payload. Mixing ordinary pushes into this denominator would inflate the result.

```sql
SELECT
    COUNT(*) AS alarm_provider_success_deliveries,
    SUM(alarm_scheduled_at IS NOT NULL) AS alarm_scheduled_deliveries,
    ROUND(100.0 * SUM(alarm_scheduled_at IS NOT NULL) / NULLIF(COUNT(*), 0), 2)
        AS alarm_scheduled_percent
FROM push_deliveries
WHERE status = 'SUCCESS'
  AND payload_type = 'DEPARTURE_ALARM_SYNC'
  AND delivery_ack_capability_version = 1
  AND delivered_at >= :from_utc
  AND delivered_at < :to_utc
  AND delivered_at < UTC_TIMESTAMP(6) - INTERVAL 10 MINUTE;
```

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
count as execution coverage, but neither enters the exact-delay histogram.

Use an expected-trigger cohort, not provider delivery time alone, when calculating a push-origin
alarm fire rate. The frozen alarm command is in the matching app-notification payload. Validate the
ISO-to-`DATETIME(6)` conversion against production payload samples before adopting this query as a
dashboard source.

```sql
WITH pushed_alarm AS (
    SELECT
        d.id,
        d.alarm_scheduled_at,
        d.alarm_fired_at,
        CAST(
            REPLACE(REPLACE(
                JSON_UNQUOTE(JSON_EXTRACT(n.data_json, '$.alarmTriggerAt')),
                'T', ' '
            ), 'Z', '')
            AS DATETIME(6)
        ) AS expected_trigger_at
    FROM push_deliveries d
    JOIN app_notifications n
      ON n.member_id = d.member_id
     AND n.logical_event_key = d.event_key
    WHERE d.status = 'SUCCESS'
      AND d.payload_type = 'DEPARTURE_ALARM_SYNC'
      AND d.delivered_at >= :from_utc
      AND d.delivered_at < :to_utc
      AND JSON_UNQUOTE(JSON_EXTRACT(n.data_json, '$.alarmOperation')) = 'UPSERT'
), aged_scheduled AS (
    SELECT *
    FROM pushed_alarm
    WHERE alarm_scheduled_at IS NOT NULL
      AND expected_trigger_at < UTC_TIMESTAMP(6) - INTERVAL 10 MINUTE
)
SELECT
    COUNT(*) AS expected_push_origin_fires,
    SUM(alarm_fired_at IS NOT NULL) AS observed_push_origin_fires,
    ROUND(100.0 * SUM(alarm_fired_at IS NOT NULL) / NULLIF(COUNT(*), 0), 2)
        AS observed_fire_percent
FROM aged_scheduled;
```

Snapshot and push command applications are persisted as device-bound receipts. This makes the aged
scheduled cohort an explicit denominator. Deduplicate replays by the server-generated command
receipt key and report delivery mode separately. Android exact callbacks, iOS observed alerting,
and iOS inferred one-shot delivery must remain separate timing-basis cohorts.

```sql
WITH scheduled AS (
    SELECT
        member_id,
        device_fingerprint,
        alarm_id,
        generation,
        trigger_at,
        platform,
        delivery_mode,
        MIN(source) AS receipt_source
    FROM departure_alarm_schedule_receipts
    WHERE outcome = 'SCHEDULED'
      AND server_recorded_at >= :from_utc
      AND server_recorded_at < :to_utc
    GROUP BY
        member_id, device_fingerprint, alarm_id, generation, trigger_at,
        platform, delivery_mode
), aged AS (
    SELECT *
    FROM scheduled
    WHERE trigger_at < UTC_TIMESTAMP(6) - INTERVAL 10 MINUTE
)
SELECT
    m.platform,
    m.delivery_mode,
    m.receipt_source,
    COUNT(*) AS expected_fires,
    SUM(EXISTS (
        SELECT 1
        FROM departure_alarm_fire_events f
        WHERE f.member_id = m.member_id
          AND f.device_fingerprint = m.device_fingerprint
          AND f.alarm_id = m.alarm_id
          AND f.generation = m.generation
          AND (f.source_trigger_at = m.trigger_at OR f.scheduled_for = m.trigger_at)
    )) AS observed_fires,
    SUM(EXISTS (
        SELECT 1
        FROM departure_alarm_fire_events f
        WHERE f.member_id = m.member_id
          AND f.device_fingerprint = m.device_fingerprint
          AND f.alarm_id = m.alarm_id
          AND f.generation = m.generation
          AND f.timing_basis = 'EXACT_CALLBACK'
          AND (f.source_trigger_at = m.trigger_at OR f.scheduled_for = m.trigger_at)
    )) AS exact_callback_fires,
    SUM(EXISTS (
        SELECT 1
        FROM departure_alarm_fire_events f
        WHERE f.member_id = m.member_id
          AND f.device_fingerprint = m.device_fingerprint
          AND f.alarm_id = m.alarm_id
          AND f.generation = m.generation
          AND f.timing_basis = 'OBSERVED_ALERTING'
          AND (f.source_trigger_at = m.trigger_at OR f.scheduled_for = m.trigger_at)
    )) AS observed_alerting_fires,
    SUM(EXISTS (
        SELECT 1
        FROM departure_alarm_fire_events f
        WHERE f.member_id = m.member_id
          AND f.device_fingerprint = m.device_fingerprint
          AND f.alarm_id = m.alarm_id
          AND f.generation = m.generation
          AND f.timing_basis = 'INFERRED_OS_DELIVERY'
          AND (f.source_trigger_at = m.trigger_at OR f.scheduled_for = m.trigger_at)
    )) AS inferred_os_delivery_fires,
    ROUND(100.0 * SUM(EXISTS (
        SELECT 1
        FROM departure_alarm_fire_events f
        WHERE f.member_id = m.member_id
          AND f.device_fingerprint = m.device_fingerprint
          AND f.alarm_id = m.alarm_id
          AND f.generation = m.generation
          AND (f.source_trigger_at = m.trigger_at OR f.scheduled_for = m.trigger_at)
    )) / NULLIF(COUNT(*), 0), 2) AS observed_fire_percent
FROM aged m
GROUP BY m.platform, m.delivery_mode, m.receipt_source;
```

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
`alarm_scheduled >= 98%` of aged alarm-control provider successes,
`alarm_fired >= 98%` of aged push-origin alarms confirmed scheduled by the client, and, for each
sufficiently sampled ETA slice, `MAE <= 300 seconds`, `P90 absolute error <= 600 seconds`, and
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
