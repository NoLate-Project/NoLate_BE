package com.noLate.notification.liveactivity.domain

import com.noLate.global.config.ProductionSchemaVersionGuard
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class LiveActivityMigrationContractTest {
    private val schema = Files.readString(Path.of("src/main/resources/schema.sql"))
    private val migration = Files.readString(
        Path.of("docs/schedule/migrations/2026-08-06-live-activity-delivery.sql")
    )
    private val appearanceMigration = Files.readString(
        Path.of("docs/schedule/migrations/2026-08-06-live-activity-appearance.sql")
    )

    @Test
    fun `bootstrap and production migration expose identical critical fences`() {
        val tables = listOf(
            "live_activity_lifecycle_intents",
            "live_activity_start_tokens",
            "schedule_live_activities",
            "live_activity_deliveries",
        )
        tables.forEach { table ->
            assertThat(schema).contains("CREATE TABLE IF NOT EXISTS $table")
            assertThat(migration).contains("CREATE TABLE IF NOT EXISTS $table")
        }
        val criticalFragments = listOf(
            "idx_live_activity_lifecycle_intent_pending",
            "dead_lettered_at DATETIME(6)",
            "lifecycle_intent_pending\n        (completed_at, dead_lettered_at, available_at, id)",
            "chk_live_activity_lifecycle_intent_scope",
            "idx_schedule_live_activity_terminal_retention",
            "uk_live_activity_start_token_device_type",
            "uk_live_activity_start_token_fingerprint",
            "uk_schedule_live_activity_schedule_member_device",
            "uk_schedule_live_activity_update_token_fingerprint",
            "source_notification_generation BIGINT NOT NULL",
            "update_token_generation BIGINT NULL",
            "late_end_ack_session_generation BIGINT NULL",
            "late_end_ack_push_device_token_id BIGINT NULL",
            "late_end_ack_fcm_ownership_version BIGINT NULL",
            "'TOKEN_PENDING'",
            "uk_live_activity_delivery_event_key",
            "idx_live_activity_delivery_due",
            "'UNCERTAIN'",
            "dispatch_lease_id VARCHAR(64)",
        )
        criticalFragments.forEach { fragment ->
            assertThat(schema).contains(fragment)
            assertThat(migration).contains(fragment)
        }
    }

    @Test
    fun `production migration is guarded and records exact marker`() {
        assertThat(migration).contains(
            "assert_live_activity_preconditions",
            "assert_live_activity_postconditions",
            "2026-08-06-live-activity-delivery-v1",
            "ActivityKit state, credentials, and immutable provider outbox",
        )
    }

    @Test
    fun `appearance migration is additive nullable guarded and production required`() {
        val column = "appearance VARCHAR(10) NULL"
        val constraint = "chk_live_activity_start_token_appearance"

        assertThat(schema).contains(column, constraint, "appearance IN ('LIGHT', 'DARK')")
        assertThat(appearanceMigration).contains(
            "assert_live_activity_appearance_preconditions",
            "2026-08-06-live-activity-delivery-v1",
            column,
            constraint,
            "appearance IS NULL OR appearance IN ('LIGHT', 'DARK')",
            "is_nullable = 'YES'",
            "assert_live_activity_appearance_postconditions",
            ProductionSchemaVersionGuard.LIVE_ACTIVITY_APPEARANCE_SCHEMA_VERSION,
        )
        assertThat(appearanceMigration.indexOf("CALL assert_live_activity_appearance_postconditions()"))
            .isLessThan(
                appearanceMigration.indexOf(
                    "INSERT INTO application_schema_migrations(version, description, applied_at)"
                )
            )
        assertThat(ProductionSchemaVersionGuard.REQUIRED_SCHEMA_VERSIONS)
            .contains(ProductionSchemaVersionGuard.LIVE_ACTIVITY_APPEARANCE_SCHEMA_VERSION)
    }
}
