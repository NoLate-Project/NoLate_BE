-- ETA provenance for schedule push/departure-status.
-- Hibernate ddl-auto=update also adds these columns, but this script makes the production rollout
-- explicit and reviewable. Each statement is safe to run once after checking the target schema.

ALTER TABLE schedule_push_job
    ADD COLUMN last_live_fetched_at DATETIME(6) NULL
        COMMENT 'Last successful live provider fetch time' AFTER last_checked_at,
    ADD COLUMN last_eta_source VARCHAR(30) NULL
        COMMENT 'LIVE_PROVIDER, SELECTED_ROUTE, or SAVED_FALLBACK' AFTER last_live_fetched_at,
    ADD COLUMN last_eta_stale BOOLEAN NULL
        COMMENT 'Whether the last ETA used a stale snapshot' AFTER last_eta_source,
    ADD COLUMN last_eta_failure_reason VARCHAR(500) NULL
        COMMENT 'Last ETA fallback or provider failure reason' AFTER last_eta_stale,
    ADD COLUMN last_traffic_change_minutes INT NULL
        COMMENT 'Last observed ETA delta in minutes' AFTER last_eta_failure_reason,
    ADD COLUMN last_changed_at DATETIME(6) NULL
        COMMENT 'Time when ETA last changed' AFTER last_traffic_change_minutes;
