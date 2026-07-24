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
`ALTER TABLE`, so a database with zero, some, or all six columns can use the same procedure.
Do not replace the guards with one multi-column `ALTER`: that form cannot safely repair a
partially applied upgrade.

## Verify

The final two queries in the SQL file list the columns and return `expected_count`. Deployment
passes only when:

- `expected_count` is `6`;
- all six columns are nullable;
- `last_live_fetched_at` and `last_changed_at` are `datetime(6)`;
- `last_eta_source` is `varchar(30)`;
- `last_eta_stale` is the database boolean representation;
- `last_eta_failure_reason` is `varchar(500)`;
- `last_traffic_change_minutes` is `int`.

Re-run the same SQL file if the count is below six. Existing columns are reported and left
untouched; only missing columns are added.
