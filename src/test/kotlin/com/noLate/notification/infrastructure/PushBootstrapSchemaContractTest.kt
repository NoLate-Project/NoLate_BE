package com.noLate.notification.infrastructure

import com.noLate.notification.domain.PushSendHistory
import jakarta.persistence.Column
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Production uses the reviewed v4 migration, while local/bootstrap environments use schema.sql.
 * Keep both representations aligned so a green local context cannot hide a production-only
 * missing column or index.
 */
class PushBootstrapSchemaContractTest {

    @Test
    fun `bootstrap push token table contains the provider lease ownership contract`() {
        val table = tableDefinition("push_device_token")

        listOf(
            "dispatch_lease_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL",
            "dispatch_lease_until DATETIME(6) NULL",
            "retirement_requested BOOLEAN NOT NULL DEFAULT FALSE",
            "delivery_ack_capability_version INT NULL",
            "INDEX idx_push_device_token_dispatch_lease (dispatch_lease_until, id)",
            "CONSTRAINT chk_push_device_token_ack_capability " +
                "CHECK (delivery_ack_capability_version IS NULL OR " +
                "delivery_ack_capability_version = 1)",
        ).forEach { required ->
            assertTrue(table.contains(required), "schema.sql is missing: $required")
        }
    }

    @Test
    fun `bootstrap app notification table separates claims from retry budget failures`() {
        val table = tableDefinition("app_notifications")

        assertTrue(
            table.contains(
                "dispatch_failure_count INT NOT NULL DEFAULT 0 " +
                    "COMMENT 'Actual retry-budget failures; expected deferrals do not increment'",
            ),
            "schema.sql must contain app_notifications.dispatch_failure_count",
        )
    }

    @Test
    fun `push history payload is LONGTEXT in fresh Hibernate schema and corrective bootstrap`() {
        val dataJsonColumn = PushSendHistory::class.java
            .getDeclaredField("dataJson")
            .getAnnotation(Column::class.java)
        val schema = Files.readString(Path.of("src/main/resources/schema.sql"))
            .replace(Regex("\\s+"), " ")

        assertEquals("LONGTEXT", dataJsonColumn.columnDefinition)
        assertTrue(
            schema.contains(
                "ALTER TABLE push_send_history " +
                    "MODIFY COLUMN data_json LONGTEXT NOT NULL COMMENT 'Canonical provider data payload'"
            ),
        )
    }

    @Test
    fun `bootstrap source delivery and history keep typed shared resource identity`() {
        val source = tableDefinition("app_notifications")
        val delivery = tableDefinition("push_deliveries")
        val history = tableDefinition("push_send_history")

        listOf(
            "calendar_id BIGINT NULL COMMENT 'Immutable shared-calendar authorization resource id'",
            "INDEX idx_app_notifications_calendar_id (calendar_id)",
        ).forEach { required ->
            assertTrue(source.contains(required), "app_notifications is missing: $required")
        }
        listOf(
            "calendar_id BIGINT NULL COMMENT 'Frozen shared-calendar authorization resource id'",
            "INDEX idx_push_deliveries_calendar_id (calendar_id)",
        ).forEach { required ->
            assertTrue(delivery.contains(required), "push_deliveries is missing: $required")
        }
        listOf(
            "logical_event_key VARCHAR(100) NULL COMMENT 'Canonical durable outbox/source event key'",
            "category_id BIGINT NULL COMMENT 'Immutable category resource id when applicable'",
            "calendar_id BIGINT NULL COMMENT 'Immutable shared-calendar resource id when applicable'",
            "INDEX idx_push_send_history_member_event (member_id, logical_event_key)",
            "INDEX idx_push_send_history_category_member (category_id, member_id)",
            "INDEX idx_push_send_history_calendar_member (calendar_id, member_id)",
        ).forEach { required ->
            assertTrue(history.contains(required), "push_send_history is missing: $required")
        }
    }

    @Test
    fun `bootstrap schedule job keeps ETA provenance and durable delivery state together`() {
        val job = tableDefinition("schedule_push_job")

        listOf(
            "last_live_fetched_at DATETIME(6) NULL",
            "last_live_travel_minutes INT NULL",
            "last_eta_source VARCHAR(30) NULL",
            "last_eta_stale BOOLEAN NULL",
            "last_eta_failure_reason VARCHAR(500) NULL",
            "last_predicted_arrival_at DATETIME(6) NULL",
            "last_eta_travel_mode VARCHAR(20) NULL",
            "last_eta_provider_fetched_at DATETIME(6) NULL",
            "last_eta_algorithm_version VARCHAR(40) NULL",
            "last_eta_route_fingerprint VARCHAR(64) NULL",
            "last_traffic_change_minutes INT NULL",
            "last_changed_at DATETIME(6) NULL",
            "last_handled_departure_at DATETIME(6) NULL",
            "last_handled_reminder_boundary_at DATETIME(6) NULL",
            "handled_departure_notice_at DATETIME(6) NULL",
            "last_uncertain_at DATETIME(6) NULL",
            "notification_generation BIGINT NOT NULL DEFAULT 0",
            "notification_input_fingerprint VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL",
        ).forEach { required ->
            assertTrue(job.contains(required), "schedule_push_job is missing integrated field: $required")
        }
    }

    @Test
    fun `bootstrap delivery and ETA observation keep last mile measurement fields`() {
        val delivery = tableDefinition("push_deliveries")
        val departureStatus = tableDefinition("schedule_departure_statuses")
        val observation = tableDefinition("schedule_eta_accuracy_observations")

        listOf(
            "delivery_ack_capability_version INT NULL",
            "client_received_at DATETIME(6) NULL",
            "client_presented_at DATETIME(6) NULL",
            "alarm_scheduled_at DATETIME(6) NULL",
            "alarm_fired_at DATETIME(6) NULL",
            "client_actioned_at DATETIME(6) NULL",
            "client_ack_recorded_at DATETIME(6) NULL",
            "INDEX idx_push_deliveries_reliability_cohort " +
                "(status, delivered_at, delivery_ack_capability_version, client_received_at)",
            "CONSTRAINT chk_push_deliveries_ack_capability " +
                "CHECK (delivery_ack_capability_version IS NULL OR " +
                "delivery_ack_capability_version = 1)",
        ).forEach { required ->
            assertTrue(delivery.contains(required), "push_deliveries is missing: $required")
        }
        listOf(
            "eta_snapshot_push_job_id BIGINT NULL",
            "eta_snapshot_evaluated_at DATETIME(6) NULL",
            "eta_snapshot_recommended_departure_at DATETIME(6) NULL",
            "eta_snapshot_predicted_arrival_at DATETIME(6) NULL",
            "eta_snapshot_source VARCHAR(30) NULL",
            "eta_snapshot_stale BOOLEAN NULL",
            "eta_snapshot_travel_minutes INT NULL",
            "eta_snapshot_prediction_basis VARCHAR(40) NULL",
            "eta_snapshot_travel_mode VARCHAR(20) NULL",
            "eta_snapshot_provider_id VARCHAR(30) NULL",
            "eta_snapshot_target_arrival_at DATETIME(6) NULL",
            "eta_snapshot_on_time_arrival_possible BOOLEAN NULL",
            "eta_snapshot_algorithm_version VARCHAR(40) NULL",
            "eta_snapshot_provider_fetched_at DATETIME(6) NULL",
            "eta_observation_exposed_at DATETIME(6) NULL",
            "eta_observation_exposed_client_app_version VARCHAR(64) NULL",
            "eta_observation_exposed_client_build_version VARCHAR(64) NULL",
            "eta_observation_exposed_ux_variant VARCHAR(64) NULL",
            "eta_observation_prompted_at DATETIME(6) NULL",
            "eta_observation_prompted_client_app_version VARCHAR(64) NULL",
            "eta_observation_prompted_client_build_version VARCHAR(64) NULL",
            "eta_observation_prompted_ux_variant VARCHAR(64) NULL",
            "eta_observation_responded_at DATETIME(6) NULL",
            "UNIQUE KEY uk_schedule_departure_statuses_schedule_member (schedule_id, member_id)",
        ).forEach { required ->
            assertTrue(
                departureStatus.contains(required),
                "schedule_departure_statuses is missing: $required",
            )
        }
        listOf(
            "predicted_arrival_at DATETIME(6) NOT NULL",
            "recommended_departure_at DATETIME(6) NOT NULL",
            "target_arrival_at DATETIME(6) NOT NULL",
            "actual_arrival_at DATETIME(6) NOT NULL",
            "observation_source VARCHAR(30) NOT NULL",
            "precision_seconds INT NOT NULL",
            "adjustment_seconds INT NULL",
            "prediction_basis VARCHAR(40) NOT NULL",
            "travel_mode VARCHAR(20) NOT NULL",
            "provider_id VARCHAR(30) NOT NULL",
            "algorithm_version VARCHAR(40) NOT NULL",
            "provider_fetched_at DATETIME(6) NULL",
            "predicted_on_time BOOLEAN NOT NULL",
            "actual_on_time BOOLEAN NOT NULL",
            "on_time_outcome VARCHAR(50) NOT NULL",
            "departure_offset_seconds BIGINT NOT NULL",
            "accuracy_eligible BOOLEAN NOT NULL",
            "signed_error_seconds BIGINT NOT NULL",
            "absolute_error_seconds BIGINT NOT NULL",
            "UNIQUE KEY uk_eta_accuracy_schedule_member (schedule_id, member_id)",
            "INDEX idx_eta_accuracy_source (eta_source, recorded_at)",
            "INDEX idx_eta_accuracy_observation_quality (accuracy_eligible, observation_source, precision_seconds, recorded_at)",
            "INDEX idx_eta_accuracy_provenance (algorithm_version, travel_mode, provider_id, prediction_basis, recorded_at)",
            "FOREIGN KEY (schedule_id) REFERENCES schedules (id) ON DELETE CASCADE",
            "CONSTRAINT chk_eta_accuracy_predicted_on_time",
            "CONSTRAINT chk_eta_accuracy_actual_on_time",
            "CONSTRAINT chk_eta_accuracy_on_time_outcome",
            "CONSTRAINT chk_eta_accuracy_observation_source",
            "CONSTRAINT chk_eta_accuracy_precision_seconds",
            "CONSTRAINT chk_eta_accuracy_observation_shape",
            "'UNVERIFIED_DEPARTURE'",
            "prediction_basis = 'PROVIDER_ABSOLUTE'",
            "CONSTRAINT chk_eta_accuracy_actual_after_departure",
        ).forEach { required ->
            assertTrue(
                observation.contains(required),
                "schedule_eta_accuracy_observations is missing: $required",
            )
        }
    }

    @Test
    fun `manual migration fails closed for revoked legacy share source and history`() {
        val migration = Files.readString(
            Path.of("docs/notification/2026-07-24-push-delivery-linearization.sql")
        ).replace(Regex("\\s+"), " ")

        listOf(
            "ADD COLUMN calendar_id BIGINT NULL COMMENT 'Immutable shared-calendar authorization resource id'",
            "ADD INDEX idx_app_notifications_calendar_id (calendar_id)",
            "ADD COLUMN calendar_id BIGINT NULL COMMENT 'Frozen shared-calendar authorization resource id'",
            "ADD INDEX idx_push_deliveries_calendar_id (calendar_id)",
            "ALTER TABLE push_send_history ADD COLUMN logical_event_key VARCHAR(100) NULL",
            "ADD INDEX idx_push_send_history_member_event (member_id, logical_event_key)",
            "ADD INDEX idx_push_send_history_category_member (category_id, member_id)",
            "ADD INDEX idx_push_send_history_calendar_member (calendar_id, member_id)",
            "drain revoked legacy category share notifications",
            "drain revoked legacy calendar share notifications",
            "drain revoked legacy category share history",
            "drain revoked legacy calendar share history",
            "revoked category share source remains",
            "revoked calendar share source remains",
            "revoked category share history remains",
            "revoked calendar share history remains",
            "revoked_category_share_histories",
            "revoked_calendar_share_histories",
        ).forEach { required ->
            assertTrue(migration.contains(required), "manual migration is missing: $required")
        }
    }

    private fun tableDefinition(tableName: String): String {
        val schema = Files.readString(Path.of("src/main/resources/schema.sql"))
            .replace(Regex("\\s+"), " ")
        val start = schema.indexOf("CREATE TABLE IF NOT EXISTS $tableName (")
        check(start >= 0) { "schema.sql has no $tableName table" }
        val end = schema.indexOf(") COMMENT=", start)
        check(end > start) { "schema.sql has no complete $tableName table" }
        return schema.substring(start, end)
    }
}
