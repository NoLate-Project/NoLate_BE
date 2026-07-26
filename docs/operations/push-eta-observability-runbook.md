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
every other `/actuator/**` route remain denied.

## Scrape access

The Prometheus registry is enabled, but the HTTP scrape route fails closed. Missing, false, and
malformed values do not open it. The only public actuator request that can be enabled is the exact
`GET /actuator/prometheus`.

```text
OBSERVABILITY_PROMETHEUS_PUBLIC_ENABLED=true
```

Set this value only after the deployment ingress or private network restricts the path to the
approved scraper. Do not expose an unauthenticated scrape route to the public Internet. A normal
member JWT cannot unlock actuator routes when the flag is off, and enabling the flag does not open
POST, a trailing-slash subpath, `/actuator/health`, or `/actuator/metrics`.

To close the route, remove the value or set it to anything other than exact lowercase `true`, then
restart the application.

## Metric contract

Every meter has the stable `application` common tag. All metric-specific tags are finite enums
controlled by source code. No metric tag contains a member, schedule, calendar, token, device,
provider message ID, exception class, error message, title, body, or raw payload.

| Metric | Type | Bounded dimensions | Meaning |
|---|---|---|---|
| `nolate_push_delivery_claims_total` | counter | `outcome` | Durable delivery claim result, including pre-existing ambiguous boundaries |
| `nolate_push_delivery_uncertain_total` | counter | `reason` | Provider-unknown or locally unrecorded terminal outcomes |
| `nolate_push_provider_duration_seconds` | histogram/timer | `outcome` | FCM/provider call result and latency |
| `nolate_push_token_lease_total` | counter | `outcome` | Token ownership lease acquired, busy, deferred, or superseded |
| `nolate_push_outbox_events_total` | counter | `outcome` | Claim, completion, retry, terminal failure, deferral, recovery, or lost lease |
| `nolate_eta_jobs_total` | counter | `outcome` | ETA worker claim, processing, retry, recovery, failure, and uncertain delivery |
| `nolate_eta_resolutions_total` | counter | `source`, `quality` | Live, selected-route, or saved fallback resolution |
| `nolate_eta_provider_duration_seconds` | histogram/timer | `outcome` | Live TMAP request latency and stable failure category |
| `nolate_eta_jobs_due` | gauge | none | Number of ETA jobs whose next check is overdue |
| `nolate_eta_jobs_oldest_delay_seconds` | gauge | none | Age of the oldest overdue ETA job |
| `nolate_push_outbox_events_due` | gauge | none | Number of due outbox events |
| `nolate_push_outbox_oldest_delay_seconds` | gauge | none | Age of the oldest due outbox event |
| `nolate_push_outbox_leases_stale` | gauge | none | Outbox leases older than the processing timeout |
| `nolate_push_deliveries_ambiguous` | gauge | none | `DISPATCHING` deliveries older than the provider call bound |
| `nolate_push_token_leases_expired` | gauge | none | Expired token leases awaiting cleanup |
| `nolate_observability_snapshot_failures_total` | counter | none | Database snapshot sampling failures |

Backlog gauges never query the database during a scrape. A dedicated daemon sampler updates
in-memory values every 30 seconds by default:

```text
OBSERVABILITY_SNAPSHOT_ENABLED=true
OBSERVABILITY_SNAPSHOT_FIXED_DELAY_MS=30000
OBSERVABILITY_SNAPSHOT_INITIAL_DELAY_MS=30000
```

The queries use the existing state/time indexes:

- `schedule_push_job(status, next_check_at)`
- `app_notifications(dispatch_status, next_dispatch_at, id)`
- `app_notifications(dispatch_status, dispatch_locked_at, id)`
- `push_deliveries(status, last_attempted_at)`
- `push_device_token(dispatch_lease_until, id)`

They are read-only and do not acquire pessimistic locks. If sampling fails, application requests
and provider state transitions continue, the gauges retain their last known values, and only the
snapshot failure counter increases. Metric registry failures are also isolated from provider return
values and durable state transitions.

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
   ```

8. Tune thresholds from staging baseline, load the rules into the actual Prometheus-compatible
   system, attach notification routing, and capture links/screenshots as external release evidence.
   Load them per environment, or add the monitoring system's environment/cluster labels to every
   selector before using a shared multi-environment rule evaluator.

Recommended dashboard panels are ETA/outbox due count and oldest delay, stale/expired leases,
ambiguous deliveries, push outcome rate and latency, live ETA provider failure rate and latency,
and fallback quality ratio.

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

Crash reporting and alert delivery remain external release work. Connecting a selected SDK/account,
uploading native symbols, defining retention/access, provisioning the scrape target, importing the
dashboard, and testing the paging route must be completed and evidenced in the deployment
environment.
