-- One-time reconciliation for the 2026-08-02 production database state.
--
-- A previous Hibernate ddl-auto=update run created the additive tables, columns, and indexes
-- for seven reviewed migrations without their verified markers. This script is intentionally
-- fail-closed for that exact partial state. It must not be reused as a general migration.
-- Keep every API and worker stopped, take a verified backup, and run this file with a MySQL
-- client that aborts on the first error.

DROP PROCEDURE IF EXISTS assert_20260802_partial_schema_preconditions;
DELIMITER //
CREATE PROCEDURE assert_20260802_partial_schema_preconditions()
BEGIN
    IF (
        SELECT COUNT(*)
        FROM application_schema_migrations
        WHERE version IN (
            '2026-07-24-push-reliability-v4',
            '2026-07-26-apple-token-lifecycle-v1',
            '2026-07-26-account-deletion-v1',
            '2026-07-27-schedule-calendar-cache-revision-v1'
        )
    ) <> 4 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'partial-schema reconciliation blocked: predecessor markers differ';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM application_schema_migrations
        WHERE version IN (
            '2026-07-29-departure-alarm-mode-v1',
            '2026-07-29-departure-alarm-sync-v1',
            '2026-07-31-push-eta-trust-v1',
            '2026-08-01-departure-alarm-fire-evidence-v1',
            '2026-08-01-departure-alarm-schedule-receipts-v1',
            '2026-08-01-sharing-safety-v1',
            '2026-08-01-push-delivery-ack-capability-v1'
        )
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'partial-schema reconciliation blocked: a target marker already exists';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name IN ('schedule_routes', 'schedule_travel_plans')
          AND column_name = 'alert_mode'
          AND data_type = 'varchar'
          AND character_maximum_length = 20
          AND is_nullable = 'NO'
          AND column_default = 'STANDARD'
    ) <> 2 OR EXISTS (
        SELECT 1 FROM schedule_routes WHERE alert_mode NOT IN ('STANDARD', 'ALARM') LIMIT 1
    ) OR EXISTS (
        SELECT 1 FROM schedule_travel_plans WHERE alert_mode NOT IN ('STANDARD', 'ALARM') LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'partial-schema reconciliation blocked: alarm-mode contract differs';
    END IF;

    -- Hibernate created BIT(1) without a database default. This exact drift is repaired below.
    IF (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'app_notifications'
          AND column_name = 'inbox_visible'
          AND column_type = 'bit(1)'
          AND is_nullable = 'NO'
          AND column_default IS NULL
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'partial-schema reconciliation blocked: inbox visibility drift differs';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name IN (
              'departure_alarm_sync_state',
              'schedule_eta_accuracy_observations',
              'departure_alarm_fire_events',
              'departure_alarm_schedule_receipts',
              'sharing_member_blocks',
              'sharing_reports',
              'schedule_share_invitation_acceptances'
          )
    ) <> 7 OR (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_sync_state'
          AND column_name IN (
              'id', 'version', 'member_id', 'schedule_id', 'alarm_id', 'generation',
              'operation', 'trigger_at', 'title', 'snooze_minutes', 'command_fingerprint',
              'create_dt', 'update_dt'
          )
    ) <> 13 OR (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'push_deliveries'
          AND column_name IN (
              'client_received_at', 'client_presented_at', 'alarm_scheduled_at',
              'alarm_fired_at', 'client_actioned_at', 'client_ack_recorded_at'
          )
    ) <> 6 OR (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_push_job'
          AND column_name IN (
              'last_predicted_arrival_at', 'last_eta_travel_mode',
              'last_eta_provider_fetched_at', 'last_eta_algorithm_version'
          )
    ) <> 4 OR (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_departure_statuses'
          AND column_name IN (
              'eta_snapshot_push_job_id', 'eta_snapshot_evaluated_at',
              'eta_snapshot_recommended_departure_at', 'eta_snapshot_predicted_arrival_at',
              'eta_snapshot_source', 'eta_snapshot_stale', 'eta_snapshot_travel_minutes',
              'eta_snapshot_prediction_basis', 'eta_snapshot_travel_mode',
              'eta_snapshot_provider_id', 'eta_snapshot_target_arrival_at',
              'eta_snapshot_on_time_arrival_possible',
              'eta_snapshot_algorithm_version', 'eta_snapshot_provider_fetched_at',
              'eta_observation_exposed_at', 'eta_observation_prompted_at',
              'eta_observation_exposed_client_app_version',
              'eta_observation_exposed_client_build_version',
              'eta_observation_exposed_ux_variant',
              'eta_observation_prompted_client_app_version',
              'eta_observation_prompted_client_build_version',
              'eta_observation_prompted_ux_variant',
              'eta_observation_responded_at'
          )
    ) <> 23 OR (
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_eta_accuracy_observations'
    ) <> 37 OR (
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_fire_events'
    ) <> 16 OR (
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_schedule_receipts'
    ) <> 22 OR (
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'sharing_reports'
          AND column_name IN ('moderator_member_id', 'resolution_note', 'resolved_at')
    ) <> 3 OR (
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name IN ('push_device_token', 'push_deliveries')
          AND column_name = 'delivery_ack_capability_version'
          AND data_type = 'int'
          AND is_nullable = 'YES'
    ) <> 2 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'partial-schema reconciliation blocked: table or column contract differs';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM (
            SELECT table_name, index_name
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND (
                  (table_name = 'departure_alarm_sync_state' AND index_name IN (
                      'uk_departure_alarm_sync_member_schedule', 'uk_departure_alarm_sync_alarm_id',
                      'idx_departure_alarm_sync_member_id', 'idx_departure_alarm_sync_expiry'
                  )) OR
                  (table_name = 'schedule_eta_accuracy_observations' AND index_name IN (
                      'PRIMARY', 'uk_eta_accuracy_schedule_member', 'idx_eta_accuracy_recorded_at',
                      'idx_eta_accuracy_source', 'idx_eta_accuracy_observation_quality',
                      'idx_eta_accuracy_provenance', 'idx_eta_accuracy_cohort',
                      'idx_eta_accuracy_member'
                  )) OR
                  (table_name = 'departure_alarm_fire_events' AND index_name IN (
                      'PRIMARY', 'uk_departure_alarm_fire_member_event',
                      'uk_departure_alarm_fire_member_device_trigger',
                      'idx_departure_alarm_fire_recorded_at', 'idx_departure_alarm_fire_member',
                      'idx_departure_alarm_fire_schedule'
                  )) OR
                  (table_name = 'departure_alarm_schedule_receipts' AND index_name IN (
                      'PRIMARY', 'uk_departure_alarm_receipt_member_client',
                      'uk_departure_alarm_receipt_member_device_command',
                      'idx_departure_alarm_receipt_cohort', 'idx_departure_alarm_receipt_schedule',
                      'idx_departure_alarm_receipt_member'
                  )) OR
                  (table_name = 'sharing_member_blocks' AND index_name IN (
                      'PRIMARY', 'uk_sharing_member_blocks_pair',
                      'idx_sharing_member_blocks_blocker', 'idx_sharing_member_blocks_blocked'
                  )) OR
                  (table_name = 'sharing_reports' AND index_name IN (
                      'PRIMARY', 'idx_sharing_reports_reporter_created',
                      'idx_sharing_reports_status_created', 'idx_sharing_reports_resource'
                  )) OR
                  (table_name = 'schedule_share_invitation_acceptances' AND index_name IN (
                      'PRIMARY', 'uk_share_invitation_acceptance_member',
                      'idx_share_invitation_acceptance_member'
                  )) OR
                  (table_name = 'push_deliveries' AND
                      index_name = 'idx_push_deliveries_reliability_cohort')
              )
            GROUP BY table_name, index_name
        ) expected_indexes
    ) <> 36 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'partial-schema reconciliation blocked: required indexes differ';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM information_schema.referential_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'schedule_eta_accuracy_observations'
          AND constraint_name = 'fk_eta_accuracy_schedule'
          AND referenced_table_name = 'schedules'
          AND delete_rule = 'CASCADE'
    ) <> 1 OR (
        SELECT COUNT(*)
        FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND constraint_type = 'CHECK'
          AND (
              (table_name = 'departure_alarm_sync_state' AND constraint_name IN (
                  'chk_departure_alarm_sync_generation', 'chk_departure_alarm_sync_operation',
                  'chk_departure_alarm_sync_shape'
              )) OR
              (table_name = 'departure_alarm_fire_events' AND constraint_name IN (
                  'chk_departure_alarm_fire_generation',
                  'chk_departure_alarm_fire_desired_generation',
                  'chk_departure_alarm_fire_operation', 'chk_departure_alarm_fire_timing_basis',
                  'chk_departure_alarm_fire_relation'
              )) OR
              (table_name = 'departure_alarm_schedule_receipts' AND constraint_name IN (
                  'chk_departure_alarm_receipt_generation',
                  'chk_departure_alarm_receipt_desired_generation',
                  'chk_departure_alarm_receipt_relation', 'chk_departure_alarm_receipt_enums',
                  'chk_departure_alarm_receipt_shape'
              )) OR
              (table_name = 'sharing_member_blocks' AND
                  constraint_name = 'chk_sharing_member_blocks_not_self') OR
              (table_name = 'sharing_reports' AND
                  constraint_name = 'chk_sharing_reports_not_self')
          )
    ) <> 15 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'partial-schema reconciliation blocked: existing constraints differ';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'schedule_eta_accuracy_observations'
          AND constraint_type = 'CHECK'
    ) <> 2 OR (
        SELECT COUNT(*)
        FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'schedule_eta_accuracy_observations'
          AND constraint_type = 'CHECK'
          AND constraint_name IN (
              'chk_eta_accuracy_travel_minutes', 'chk_eta_accuracy_absolute_error'
          )
    ) <> 2 OR EXISTS (
        SELECT 1
        FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND constraint_type = 'CHECK'
          AND constraint_name IN (
              'chk_push_device_token_ack_capability',
              'chk_push_deliveries_ack_capability'
          )
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'partial-schema reconciliation blocked: known missing checks differ';
    END IF;

    -- The affected newly-created tables were observed empty. Refuse to certify unreviewed rows.
    IF EXISTS (SELECT 1 FROM departure_alarm_sync_state LIMIT 1)
       OR EXISTS (SELECT 1 FROM schedule_eta_accuracy_observations LIMIT 1)
       OR EXISTS (SELECT 1 FROM departure_alarm_fire_events LIMIT 1)
       OR EXISTS (SELECT 1 FROM departure_alarm_schedule_receipts LIMIT 1)
       OR EXISTS (SELECT 1 FROM sharing_member_blocks LIMIT 1)
       OR EXISTS (SELECT 1 FROM sharing_reports LIMIT 1)
       OR EXISTS (SELECT 1 FROM schedule_share_invitation_acceptances LIMIT 1)
       OR EXISTS (
           SELECT 1 FROM push_device_token
           WHERE delivery_ack_capability_version IS NOT NULL
             AND delivery_ack_capability_version <> 1
           LIMIT 1
       ) OR EXISTS (
           SELECT 1 FROM push_deliveries
           WHERE delivery_ack_capability_version IS NOT NULL
             AND delivery_ack_capability_version <> 1
           LIMIT 1
       ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'partial-schema reconciliation blocked: affected data requires review';
    END IF;
END//
DELIMITER ;

CALL assert_20260802_partial_schema_preconditions();
DROP PROCEDURE assert_20260802_partial_schema_preconditions;

ALTER TABLE app_notifications
    MODIFY COLUMN inbox_visible BOOLEAN NOT NULL DEFAULT TRUE
        COMMENT 'Whether this durable row appears in the user inbox and unread count'
        AFTER created_at;

ALTER TABLE schedule_eta_accuracy_observations
    ADD CONSTRAINT chk_eta_accuracy_observation_source
        CHECK (observation_source IN ('USER_NOW', 'USER_ADJUSTED', 'GEOFENCE')),
    ADD CONSTRAINT chk_eta_accuracy_observation_verification
        CHECK (observation_verification IN ('UNVERIFIED_CLIENT', 'VERIFIED_GEOFENCE')),
    ADD CONSTRAINT chk_eta_accuracy_precision_seconds
        CHECK (precision_seconds BETWEEN 1 AND 3600),
    ADD CONSTRAINT chk_eta_accuracy_observation_shape CHECK (
        (
            observation_source = 'USER_ADJUSTED' AND
            adjustment_seconds IS NOT NULL AND
            adjustment_seconds BETWEEN 60 AND 3600 AND
            MOD(adjustment_seconds, 60) = 0 AND
            precision_seconds >= 60
        ) OR (
            observation_source IN ('USER_NOW', 'GEOFENCE') AND
            adjustment_seconds IS NULL
        )
    ),
    ADD CONSTRAINT chk_eta_accuracy_client_cohort CHECK (
        (client_app_version IS NULL OR client_app_version REGEXP '^[A-Za-z0-9._+-]{1,64}$') AND
        (client_build_version IS NULL OR client_build_version REGEXP '^[A-Za-z0-9._+-]{1,64}$')
    ),
    ADD CONSTRAINT chk_eta_accuracy_backend_cohort
        CHECK (backend_cohort_version REGEXP '^[A-Za-z0-9._+-]{1,64}$'),
    ADD CONSTRAINT chk_eta_accuracy_eligibility_policy
        CHECK (eligibility_policy_version = 'SELF_REPORT_DIAGNOSTIC_V2'),
    ADD CONSTRAINT chk_eta_accuracy_eligibility_reason CHECK (
        accuracy_eligibility_reason IN (
            'ELIGIBLE', 'UNVERIFIED_USER_NOW', 'UNVERIFIED_USER_ADJUSTED',
            'UNVERIFIED_GEOFENCE', 'MISSING_CLIENT_APP_VERSION',
            'MISSING_CLIENT_BUILD_VERSION', 'UNVERSIONED_BACKEND_COHORT',
            'UNKNOWN_ALGORITHM_VERSION', 'UNVERIFIED_DEPARTURE',
            'OBSERVATION_PRECISION_TOO_COARSE',
            'STALE_ETA', 'UNSUPPORTED_ETA_SOURCE', 'UNSUPPORTED_PROVIDER',
            'MISSING_PROVIDER_FETCH_TIME', 'PROVIDER_FETCH_AFTER_DEPARTURE',
            'PROVIDER_PREDICTION_TOO_OLD', 'PREDICTION_EVALUATED_AFTER_DEPARTURE',
            'PREDICTION_TOO_OLD', 'PROVIDER_ABSOLUTE_DEPARTURE_OFFSET_TOO_LARGE',
            'ACTUAL_TRAVEL_DURATION_IMPLAUSIBLE'
        )
    ),
    ADD CONSTRAINT chk_eta_accuracy_eligibility_consistency
        CHECK (accuracy_eligible = (accuracy_eligibility_reason = 'ELIGIBLE')),
    ADD CONSTRAINT chk_eta_accuracy_eligible_provenance CHECK (
        NOT accuracy_eligible OR (
            observation_verification = 'VERIFIED_GEOFENCE' AND
            observation_source = 'GEOFENCE' AND
            client_app_version IS NOT NULL AND
            client_build_version IS NOT NULL AND
            LOWER(backend_cohort_version) <> 'unversioned' AND
            algorithm_version <> 'UNKNOWN' AND
            prediction_basis = 'PROVIDER_ABSOLUTE'
        )
    ),
    ADD CONSTRAINT chk_eta_accuracy_actual_after_departure
        CHECK (actual_arrival_at >= departed_at),
    ADD CONSTRAINT chk_eta_accuracy_actual_travel CHECK (
        actual_travel_seconds >= 0 AND
        actual_travel_seconds = TIMESTAMPDIFF(SECOND, departed_at, actual_arrival_at)
    ),
    ADD CONSTRAINT chk_eta_accuracy_report_delay CHECK (report_delay_seconds >= 0),
    ADD CONSTRAINT chk_eta_accuracy_predicted_on_time
        CHECK (predicted_on_time = (predicted_arrival_at <= target_arrival_at)),
    ADD CONSTRAINT chk_eta_accuracy_actual_on_time
        CHECK (actual_on_time = (actual_arrival_at <= target_arrival_at)),
    ADD CONSTRAINT chk_eta_accuracy_on_time_outcome CHECK (
        on_time_outcome = CASE
            WHEN predicted_on_time AND actual_on_time
                THEN 'PREDICTED_ON_TIME_ACTUAL_ON_TIME'
            WHEN predicted_on_time AND NOT actual_on_time
                THEN 'PREDICTED_ON_TIME_ACTUAL_LATE'
            WHEN NOT predicted_on_time AND actual_on_time
                THEN 'PREDICTED_LATE_ACTUAL_ON_TIME'
            ELSE 'PREDICTED_LATE_ACTUAL_LATE'
        END
    );

ALTER TABLE push_device_token
    ADD CONSTRAINT chk_push_device_token_ack_capability
        CHECK (delivery_ack_capability_version IS NULL OR delivery_ack_capability_version = 1);

ALTER TABLE push_deliveries
    ADD CONSTRAINT chk_push_deliveries_ack_capability
        CHECK (delivery_ack_capability_version IS NULL OR delivery_ack_capability_version = 1);

DROP PROCEDURE IF EXISTS assert_20260802_partial_schema_postconditions;
DELIMITER //
CREATE PROCEDURE assert_20260802_partial_schema_postconditions()
BEGIN
    IF (
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'app_notifications'
          AND column_name = 'inbox_visible'
          AND data_type = 'tinyint'
          AND is_nullable = 'NO'
          AND column_default IN ('1', 'b''1''')
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'partial-schema reconciliation failed: inbox visibility is invalid';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND table_name = 'schedule_eta_accuracy_observations'
          AND constraint_type = 'CHECK'
          AND constraint_name IN (
              'chk_eta_accuracy_travel_minutes',
              'chk_eta_accuracy_observation_source',
              'chk_eta_accuracy_observation_verification',
              'chk_eta_accuracy_precision_seconds',
              'chk_eta_accuracy_observation_shape',
              'chk_eta_accuracy_client_cohort',
              'chk_eta_accuracy_backend_cohort',
              'chk_eta_accuracy_eligibility_policy',
              'chk_eta_accuracy_eligibility_reason',
              'chk_eta_accuracy_eligibility_consistency',
              'chk_eta_accuracy_eligible_provenance',
              'chk_eta_accuracy_actual_after_departure',
              'chk_eta_accuracy_actual_travel',
              'chk_eta_accuracy_report_delay',
              'chk_eta_accuracy_absolute_error',
              'chk_eta_accuracy_predicted_on_time',
              'chk_eta_accuracy_actual_on_time',
              'chk_eta_accuracy_on_time_outcome'
          )
    ) <> 18 OR (
        SELECT COUNT(*)
        FROM information_schema.table_constraints
        WHERE constraint_schema = DATABASE()
          AND constraint_type = 'CHECK'
          AND constraint_name IN (
              'chk_push_device_token_ack_capability',
              'chk_push_deliveries_ack_capability'
          )
    ) <> 2 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'partial-schema reconciliation failed: required checks are absent';
    END IF;

    IF EXISTS (
        SELECT 1 FROM push_device_token
        WHERE delivery_ack_capability_version IS NOT NULL
          AND delivery_ack_capability_version <> 1
        LIMIT 1
    ) OR EXISTS (
        SELECT 1 FROM push_deliveries
        WHERE delivery_ack_capability_version IS NOT NULL
          AND delivery_ack_capability_version <> 1
        LIMIT 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'partial-schema reconciliation failed: unsupported ACK capability';
    END IF;
END//
DELIMITER ;

CALL assert_20260802_partial_schema_postconditions();
DROP PROCEDURE assert_20260802_partial_schema_postconditions;

START TRANSACTION;
INSERT INTO application_schema_migrations(version, description, applied_at)
VALUES
    (
        '2026-07-29-departure-alarm-mode-v1',
        'Per-member STANDARD or ALARM departure alert presentation mode',
        CURRENT_TIMESTAMP(6)
    ),
    (
        '2026-07-29-departure-alarm-sync-v1',
        'Durable native departure-alarm desired state and hidden control outbox',
        CURRENT_TIMESTAMP(6)
    ),
    (
        '2026-07-31-push-eta-trust-v1',
        'Last-mile push ACK and versioned immutable ETA on-time ground-truth measurement',
        CURRENT_TIMESTAMP(6)
    ),
    (
        '2026-08-01-departure-alarm-fire-evidence-v1',
        'Durable authenticated native departure-alarm fire evidence',
        CURRENT_TIMESTAMP(6)
    ),
    (
        '2026-08-01-departure-alarm-schedule-receipts-v1',
        'Append-only native departure-alarm scheduling denominator',
        CURRENT_TIMESTAMP(6)
    ),
    (
        '2026-08-01-sharing-safety-v1',
        'Sharing blocks, moderation lifecycle, and invitation acceptance idempotency',
        CURRENT_TIMESTAMP(6)
    ),
    (
        '2026-08-01-push-delivery-ack-capability-v1',
        'Versioned client ACK capability and indexed delivery reliability cohort',
        CURRENT_TIMESTAMP(6)
    );
COMMIT;

SELECT version, description, applied_at
FROM application_schema_migrations
WHERE version IN (
    '2026-07-29-departure-alarm-mode-v1',
    '2026-07-29-departure-alarm-sync-v1',
    '2026-07-31-push-eta-trust-v1',
    '2026-08-01-departure-alarm-fire-evidence-v1',
    '2026-08-01-departure-alarm-schedule-receipts-v1',
    '2026-08-01-sharing-safety-v1',
    '2026-08-01-push-delivery-ack-capability-v1'
)
ORDER BY version;
