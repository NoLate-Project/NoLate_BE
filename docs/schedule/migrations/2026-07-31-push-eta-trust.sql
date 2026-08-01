-- Last-mile push ACK and ETA ground-truth measurement (MySQL 8.x).
-- Stop all application instances before running. MySQL DDL commits implicitly.

DROP PROCEDURE IF EXISTS assert_push_eta_trust_preconditions;
DELIMITER //
CREATE PROCEDURE assert_push_eta_trust_preconditions()
BEGIN
    IF (
        SELECT COUNT(*) FROM application_schema_migrations
        WHERE version = '2026-07-29-departure-alarm-sync-v1'
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'push/ETA trust migration blocked: predecessor marker is absent';
    END IF;

    IF (
        SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_departure_statuses'
    ) <> 1 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'push/ETA trust migration blocked: departure status table is absent';
    END IF;

    IF EXISTS (
        SELECT 1 FROM application_schema_migrations
        WHERE version = '2026-07-31-push-eta-trust-v1'
    ) OR EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_eta_accuracy_observations'
    ) OR EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_push_job'
          AND column_name IN (
              'last_predicted_arrival_at', 'last_eta_travel_mode',
              'last_eta_provider_fetched_at', 'last_eta_algorithm_version'
          )
    ) OR EXISTS (
        SELECT 1 FROM information_schema.columns
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
    ) OR EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'push_deliveries'
          AND column_name IN (
              'client_received_at', 'client_presented_at', 'alarm_scheduled_at',
              'alarm_fired_at', 'client_actioned_at', 'client_ack_recorded_at'
          )
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'push/ETA trust migration blocked: partial or applied schema requires inspection';
    END IF;
END//
DELIMITER ;

CALL assert_push_eta_trust_preconditions();
DROP PROCEDURE assert_push_eta_trust_preconditions;

ALTER TABLE schedule_push_job
    ADD COLUMN last_predicted_arrival_at DATETIME(6) NULL
        COMMENT 'Latest provider/overlay absolute predicted arrival time'
        AFTER last_eta_failure_reason,
    ADD COLUMN last_eta_travel_mode VARCHAR(20) NULL
        COMMENT 'Travel mode used by the latest ETA snapshot'
        AFTER last_predicted_arrival_at,
    ADD COLUMN last_eta_provider_fetched_at DATETIME(6) NULL
        COMMENT 'Provider fetch time for latest ETA, including timetable responses'
        AFTER last_eta_travel_mode,
    ADD COLUMN last_eta_algorithm_version VARCHAR(40) NULL
        COMMENT 'Bounded ETA calculation algorithm version'
        AFTER last_eta_provider_fetched_at;

ALTER TABLE schedule_departure_statuses
    ADD COLUMN eta_snapshot_push_job_id BIGINT NULL
        COMMENT 'Frozen ETA job id without lifecycle FK coupling' AFTER departed_at,
    ADD COLUMN eta_snapshot_evaluated_at DATETIME(6) NULL
        COMMENT 'Frozen ETA evaluation time' AFTER eta_snapshot_push_job_id,
    ADD COLUMN eta_snapshot_recommended_departure_at DATETIME(6) NULL
        COMMENT 'Frozen recommended departure time' AFTER eta_snapshot_evaluated_at,
    ADD COLUMN eta_snapshot_predicted_arrival_at DATETIME(6) NULL
        COMMENT 'Frozen predicted destination arrival time' AFTER eta_snapshot_recommended_departure_at,
    ADD COLUMN eta_snapshot_source VARCHAR(30) NULL
        COMMENT 'Frozen ETA source' AFTER eta_snapshot_predicted_arrival_at,
    ADD COLUMN eta_snapshot_stale BOOLEAN NULL
        COMMENT 'Frozen ETA stale flag' AFTER eta_snapshot_source,
    ADD COLUMN eta_snapshot_travel_minutes INT NULL
        COMMENT 'Frozen ETA travel minutes' AFTER eta_snapshot_stale,
    ADD COLUMN eta_snapshot_prediction_basis VARCHAR(40) NULL
        COMMENT 'PROVIDER_ABSOLUTE or DEPARTURE_ANCHORED_DURATION'
        AFTER eta_snapshot_travel_minutes,
    ADD COLUMN eta_snapshot_travel_mode VARCHAR(20) NULL
        COMMENT 'Frozen travel mode' AFTER eta_snapshot_prediction_basis,
    ADD COLUMN eta_snapshot_provider_id VARCHAR(30) NULL
        COMMENT 'Bounded ETA provider id' AFTER eta_snapshot_travel_mode,
    ADD COLUMN eta_snapshot_target_arrival_at DATETIME(6) NULL
        COMMENT 'Frozen target arrival time' AFTER eta_snapshot_provider_id,
    ADD COLUMN eta_snapshot_on_time_arrival_possible BOOLEAN NULL
        COMMENT 'Whether frozen prediction meets target arrival'
        AFTER eta_snapshot_target_arrival_at,
    ADD COLUMN eta_snapshot_algorithm_version VARCHAR(40) NULL
        COMMENT 'Bounded frozen ETA algorithm version'
        AFTER eta_snapshot_on_time_arrival_possible,
    ADD COLUMN eta_snapshot_provider_fetched_at DATETIME(6) NULL
        COMMENT 'Frozen provider response fetch time'
        AFTER eta_snapshot_algorithm_version,
    ADD COLUMN eta_observation_exposed_at DATETIME(6) NULL
        COMMENT 'First server-observed arrival-record UI exposure'
        AFTER eta_snapshot_provider_fetched_at,
    ADD COLUMN eta_observation_exposed_client_app_version VARCHAR(64) NULL
        COMMENT 'App version frozen at first UI exposure'
        AFTER eta_observation_exposed_at,
    ADD COLUMN eta_observation_exposed_client_build_version VARCHAR(64) NULL
        COMMENT 'Build version frozen at first UI exposure'
        AFTER eta_observation_exposed_client_app_version,
    ADD COLUMN eta_observation_exposed_ux_variant VARCHAR(64) NULL
        COMMENT 'UX variant frozen at first UI exposure'
        AFTER eta_observation_exposed_client_build_version,
    ADD COLUMN eta_observation_prompted_at DATETIME(6) NULL
        COMMENT 'First server-observed arrival confirmation prompt'
        AFTER eta_observation_exposed_ux_variant,
    ADD COLUMN eta_observation_prompted_client_app_version VARCHAR(64) NULL
        COMMENT 'App version frozen at first confirmation prompt'
        AFTER eta_observation_prompted_at,
    ADD COLUMN eta_observation_prompted_client_build_version VARCHAR(64) NULL
        COMMENT 'Build version frozen at first confirmation prompt'
        AFTER eta_observation_prompted_client_app_version,
    ADD COLUMN eta_observation_prompted_ux_variant VARCHAR(64) NULL
        COMMENT 'UX variant frozen at first confirmation prompt'
        AFTER eta_observation_prompted_client_build_version,
    ADD COLUMN eta_observation_responded_at DATETIME(6) NULL
        COMMENT 'First persisted arrival observation receipt'
        AFTER eta_observation_prompted_ux_variant;

ALTER TABLE push_deliveries
    ADD COLUMN client_received_at DATETIME(6) NULL
        COMMENT 'Server receipt time of authenticated client RECEIVED ACK' AFTER error_message,
    ADD COLUMN client_presented_at DATETIME(6) NULL
        COMMENT 'Server receipt time of authenticated client PRESENTED ACK' AFTER client_received_at,
    ADD COLUMN alarm_scheduled_at DATETIME(6) NULL
        COMMENT 'Server receipt time of authenticated client ALARM_SCHEDULED ACK' AFTER client_presented_at,
    ADD COLUMN alarm_fired_at DATETIME(6) NULL
        COMMENT 'Server receipt time of authenticated client ALARM_FIRED ACK' AFTER alarm_scheduled_at,
    ADD COLUMN client_actioned_at DATETIME(6) NULL
        COMMENT 'Server receipt time of authenticated client ACTIONED ACK' AFTER alarm_fired_at,
    ADD COLUMN client_ack_recorded_at DATETIME(6) NULL
        COMMENT 'Server receipt time of latest first-seen client ACK' AFTER client_actioned_at;

CREATE TABLE schedule_eta_accuracy_observations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    schedule_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    push_job_id BIGINT NULL,
    departed_at DATETIME(6) NOT NULL,
    prediction_evaluated_at DATETIME(6) NOT NULL,
    predicted_arrival_at DATETIME(6) NOT NULL,
    recommended_departure_at DATETIME(6) NOT NULL,
    target_arrival_at DATETIME(6) NOT NULL,
    actual_arrival_at DATETIME(6) NOT NULL,
    observation_verification VARCHAR(30) NOT NULL,
    observation_source VARCHAR(30) NOT NULL,
    precision_seconds INT NOT NULL,
    adjustment_seconds INT NULL,
    client_app_version VARCHAR(64) NULL,
    client_build_version VARCHAR(64) NULL,
    backend_cohort_version VARCHAR(64) NOT NULL,
    eligibility_policy_version VARCHAR(50) NOT NULL,
    eta_source VARCHAR(30) NOT NULL,
    eta_stale BOOLEAN NOT NULL,
    travel_minutes INT NOT NULL,
    prediction_basis VARCHAR(40) NOT NULL,
    travel_mode VARCHAR(20) NOT NULL,
    provider_id VARCHAR(30) NOT NULL,
    algorithm_version VARCHAR(40) NOT NULL,
    provider_fetched_at DATETIME(6) NULL,
    predicted_on_time BOOLEAN NOT NULL,
    actual_on_time BOOLEAN NOT NULL,
    on_time_outcome VARCHAR(50) NOT NULL,
    departure_offset_seconds BIGINT NOT NULL,
    actual_travel_seconds BIGINT NOT NULL,
    report_delay_seconds BIGINT NOT NULL
        COMMENT 'Server receipt minus reconstructed client capture, floored at zero',
    accuracy_eligible BOOLEAN NOT NULL,
    accuracy_eligibility_reason VARCHAR(60) NOT NULL,
    signed_error_seconds BIGINT NOT NULL,
    absolute_error_seconds BIGINT NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_eta_accuracy_schedule_member (schedule_id, member_id),
    INDEX idx_eta_accuracy_recorded_at (recorded_at),
    INDEX idx_eta_accuracy_source (eta_source, recorded_at),
    INDEX idx_eta_accuracy_observation_quality (
        accuracy_eligible, observation_source, precision_seconds, recorded_at
    ),
    INDEX idx_eta_accuracy_provenance (
        algorithm_version, travel_mode, provider_id, prediction_basis, recorded_at
    ),
    INDEX idx_eta_accuracy_cohort (
        backend_cohort_version, client_app_version, algorithm_version, recorded_at
    ),
    INDEX idx_eta_accuracy_member (member_id, id),
    CONSTRAINT fk_eta_accuracy_schedule
        FOREIGN KEY (schedule_id) REFERENCES schedules (id) ON DELETE CASCADE,
    CONSTRAINT chk_eta_accuracy_travel_minutes CHECK (travel_minutes > 0),
    CONSTRAINT chk_eta_accuracy_observation_source
        CHECK (observation_source IN ('USER_NOW', 'USER_ADJUSTED', 'GEOFENCE')),
    CONSTRAINT chk_eta_accuracy_observation_verification
        CHECK (observation_verification IN ('UNVERIFIED_CLIENT', 'VERIFIED_GEOFENCE')),
    CONSTRAINT chk_eta_accuracy_precision_seconds
        CHECK (precision_seconds BETWEEN 1 AND 3600),
    CONSTRAINT chk_eta_accuracy_observation_shape CHECK (
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
    CONSTRAINT chk_eta_accuracy_client_cohort CHECK (
        (client_app_version IS NULL OR client_app_version REGEXP '^[A-Za-z0-9._+-]{1,64}$') AND
        (client_build_version IS NULL OR client_build_version REGEXP '^[A-Za-z0-9._+-]{1,64}$')
    ),
    CONSTRAINT chk_eta_accuracy_backend_cohort
        CHECK (backend_cohort_version REGEXP '^[A-Za-z0-9._+-]{1,64}$'),
    CONSTRAINT chk_eta_accuracy_eligibility_policy
        CHECK (eligibility_policy_version = 'SELF_REPORT_DIAGNOSTIC_V2'),
    CONSTRAINT chk_eta_accuracy_eligibility_reason CHECK (
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
    CONSTRAINT chk_eta_accuracy_eligibility_consistency
        CHECK (accuracy_eligible = (accuracy_eligibility_reason = 'ELIGIBLE')),
    CONSTRAINT chk_eta_accuracy_eligible_provenance CHECK (
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
    CONSTRAINT chk_eta_accuracy_actual_after_departure
        CHECK (actual_arrival_at >= departed_at),
    CONSTRAINT chk_eta_accuracy_actual_travel CHECK (
        actual_travel_seconds >= 0 AND
        actual_travel_seconds = TIMESTAMPDIFF(SECOND, departed_at, actual_arrival_at)
    ),
    CONSTRAINT chk_eta_accuracy_report_delay CHECK (report_delay_seconds >= 0),
    CONSTRAINT chk_eta_accuracy_absolute_error CHECK (absolute_error_seconds >= 0),
    CONSTRAINT chk_eta_accuracy_predicted_on_time
        CHECK (predicted_on_time = (predicted_arrival_at <= target_arrival_at)),
    CONSTRAINT chk_eta_accuracy_actual_on_time
        CHECK (actual_on_time = (actual_arrival_at <= target_arrival_at)),
    CONSTRAINT chk_eta_accuracy_on_time_outcome CHECK (
        on_time_outcome = CASE
            WHEN predicted_on_time AND actual_on_time
                THEN 'PREDICTED_ON_TIME_ACTUAL_ON_TIME'
            WHEN predicted_on_time AND NOT actual_on_time
                THEN 'PREDICTED_ON_TIME_ACTUAL_LATE'
            WHEN NOT predicted_on_time AND actual_on_time
                THEN 'PREDICTED_LATE_ACTUAL_ON_TIME'
            ELSE 'PREDICTED_LATE_ACTUAL_LATE'
        END
    )
) COMMENT='Opt-in actual-arrival ground truth for ETA accuracy';

DROP PROCEDURE IF EXISTS assert_push_eta_trust_postconditions;
DELIMITER //
CREATE PROCEDURE assert_push_eta_trust_postconditions()
BEGIN
    IF (
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'push_deliveries'
          AND column_name IN (
              'client_received_at', 'client_presented_at', 'alarm_scheduled_at',
              'alarm_fired_at', 'client_actioned_at', 'client_ack_recorded_at'
          )
    ) <> 6 OR (
        SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_push_job'
          AND column_name IN (
              'last_predicted_arrival_at', 'last_eta_travel_mode',
              'last_eta_provider_fetched_at', 'last_eta_algorithm_version'
          )
    ) <> 4 OR (
        SELECT COUNT(*) FROM information_schema.columns
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
    ) <> 37 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'push/ETA trust migration verification failed';
    END IF;

    IF (
        SELECT COUNT(*)
        FROM (
            SELECT index_name
            FROM information_schema.statistics
            WHERE table_schema = DATABASE()
              AND table_name = 'schedule_eta_accuracy_observations'
            GROUP BY index_name
            HAVING
                (index_name = 'PRIMARY'
                    AND MIN(non_unique) = 0
                    AND GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'id')
                OR (index_name = 'uk_eta_accuracy_schedule_member'
                    AND MIN(non_unique) = 0
                    AND GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'schedule_id,member_id')
                OR (index_name = 'idx_eta_accuracy_recorded_at'
                    AND MIN(non_unique) = 1
                    AND GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'recorded_at')
                OR (index_name = 'idx_eta_accuracy_source'
                    AND MIN(non_unique) = 1
                    AND GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'eta_source,recorded_at')
                OR (index_name = 'idx_eta_accuracy_observation_quality'
                    AND MIN(non_unique) = 1
                    AND GROUP_CONCAT(column_name ORDER BY seq_in_index) =
                        'accuracy_eligible,observation_source,precision_seconds,recorded_at')
                OR (index_name = 'idx_eta_accuracy_member'
                    AND MIN(non_unique) = 1
                    AND GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'member_id,id')
                OR (index_name = 'idx_eta_accuracy_provenance'
                    AND MIN(non_unique) = 1
                    AND GROUP_CONCAT(column_name ORDER BY seq_in_index) =
                        'algorithm_version,travel_mode,provider_id,prediction_basis,recorded_at')
                OR (index_name = 'idx_eta_accuracy_cohort'
                    AND MIN(non_unique) = 1
                    AND GROUP_CONCAT(column_name ORDER BY seq_in_index) =
                        'backend_cohort_version,client_app_version,algorithm_version,recorded_at')
        ) verified_indexes
    ) <> 8 OR (
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
    ) <> 18 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'push/ETA trust index, constraint, or ownership verification failed';
    END IF;
END//
DELIMITER ;

CALL assert_push_eta_trust_postconditions();
DROP PROCEDURE assert_push_eta_trust_postconditions;

INSERT INTO application_schema_migrations(version, description, applied_at)
VALUES (
    '2026-07-31-push-eta-trust-v1',
    'Last-mile push ACK and versioned immutable ETA on-time ground-truth measurement',
    CURRENT_TIMESTAMP(6)
);

SELECT COUNT(*) AS push_eta_trust_marker_count
FROM application_schema_migrations
WHERE version = '2026-07-31-push-eta-trust-v1';
