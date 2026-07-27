# ETA provenance migration runbook

This runbook is intentionally separate from the executable SQL. Apply
`migrations/2026-07-24-eta-provenance.sql` before deploying code that writes the new
`schedule_push_job` snapshot fields.

## Pre-check

1. Select the intended application database and confirm `SELECT DATABASE()`.
2. Confirm `schedule_push_job` exists in that database.
3. Inspect existing provenance columns:

   ```sql
   SELECT column_name, column_type, is_nullable
   FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'schedule_push_job'
     AND column_name LIKE 'last_%';
   ```

4. Take the normal production backup and schedule the DDL for a low-traffic window.

## Apply or repair

Run the SQL file as one session. Every column has its own `information_schema` guard and
`ALTER TABLE`, so a database with zero, some, or all eight columns can use the same procedure.
Do not replace the guards with one multi-column `ALTER`: that form cannot safely repair a
partially applied upgrade. The current contract has eight nullable provenance columns, including
the trusted live comparator and ETA route fingerprint.

Do not backfill `last_live_travel_minutes` from `last_travel_minutes`: the latter may be a selected
or saved fallback. Existing rows with incomplete provenance intentionally remain nullable and the
status API falls back to the current route until a worker writes a complete new snapshot.

## Verify

The final two queries in the SQL file list the columns and return `expected_count`. Deployment
passes only when:

- `expected_count` is `8`;
- all eight columns are nullable;
- `last_live_fetched_at` and `last_changed_at` are `datetime(6)`;
- `last_live_travel_minutes` is `int`;
- `last_eta_source` is `varchar(30)`;
- `last_eta_stale` is the database boolean representation;
- `last_eta_failure_reason` is `varchar(500)`;
- `last_eta_route_fingerprint` is `varchar(64)`;
- `last_traffic_change_minutes` is `int`.

Re-run the same SQL file if the count is below eight. Existing columns are reported and left
untouched; only missing columns are added.
