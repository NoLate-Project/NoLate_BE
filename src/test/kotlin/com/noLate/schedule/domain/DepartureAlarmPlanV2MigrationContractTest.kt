package com.noLate.schedule.domain

import com.noLate.global.config.ProductionSchemaVersionGuard
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DepartureAlarmPlanV2MigrationContractTest {
    private val migration = Files.readString(
        Path.of("docs/schedule/migrations/2026-08-04-departure-alarm-plan-v2.sql"),
    )
    private val schema = Files.readString(Path.of("src/main/resources/schema.sql"))

    @Test
    fun `preconditions run before additive plan DDL and marker follows all postconditions`() {
        val precondition = migration.indexOf("CALL assert_departure_alarm_plan_v2_preconditions()")
        val firstDdl = migration.indexOf("CALL add_departure_alarm_plan_column(")
        val postcondition = migration.indexOf("CALL assert_departure_alarm_plan_v2_postconditions()")
        val marker = migration.indexOf(
            "INSERT INTO application_schema_migrations(version, description, applied_at)",
        )

        assertThat(precondition).isGreaterThanOrEqualTo(0).isLessThan(firstDdl)
        assertThat(postcondition).isGreaterThan(firstDdl)
        assertThat(marker).isGreaterThan(postcondition)
        assertThat(migration).contains(
            "version = '2026-08-04-departure-alarm-plan-v2'",
            "required tables are absent",
        )
    }

    @Test
    fun `migration and canonical schema contain the complete ownership-safe v2 shape`() {
        listOf(migration, schema).forEach { ddl ->
            assertThat(ddl).contains(
                "alarm_plan_schema_version",
                "alarm_occurrences_json",
                "validation_requested_at",
                "validation_revision",
                "occurrence_id",
                "mutation_sequence",
                "native_alarm_covered_recipient_count",
                "platform VARCHAR(20) NOT NULL",
                "semantic_warning_visible",
                "departure_alarm_presentation_assignments",
                "uk_departure_alarm_fire_member_device_occurrence_trigger",
            )
        }
        assertThat(migration).contains(
            "DROP INDEX uk_departure_alarm_fire_member_device_trigger",
            "member_id, device_fingerprint, alarm_id, generation, occurrence_id, scheduled_for",
            "idx_departure_alarm_receipt_coverage",
            "member_id, schedule_id, generation, occurrence_id, trigger_at, device_token_id, token_ownership_version, mutation_sequence",
            "chk_departure_alarm_sync_validation_revision",
            "validation_revision BETWEEN 0 AND 9007199254740991",
            "chk_app_notifications_native_alarm_covered_count",
            "chk_departure_alarm_assignment_platform",
            "chk_departure_alarm_assignment_semantic_warning",
            "uk_departure_alarm_assignment_event_ownership",
            "idx_departure_alarm_assignment_measurement",
        )
    }

    @Test
    fun `production startup requires exactly the reviewed plan v2 marker`() {
        assertThat(ProductionSchemaVersionGuard.DEPARTURE_ALARM_PLAN_V2_SCHEMA_VERSION)
            .isEqualTo("2026-08-04-departure-alarm-plan-v2")
        assertThat(ProductionSchemaVersionGuard.REQUIRED_SCHEMA_VERSIONS)
            .contains(ProductionSchemaVersionGuard.DEPARTURE_ALARM_PLAN_V2_SCHEMA_VERSION)
    }
}
