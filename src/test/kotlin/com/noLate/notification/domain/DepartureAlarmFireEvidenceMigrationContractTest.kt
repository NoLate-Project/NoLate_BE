package com.noLate.notification.domain

import com.noLate.global.config.ProductionSchemaVersionGuard
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DepartureAlarmFireEvidenceMigrationContractTest {
    private val migration = Files.readString(
        Path.of("docs/schedule/migrations/2026-08-01-departure-alarm-fire-evidence.sql"),
    )

    @Test
    fun `predecessor partial schema and rerun are guarded before DDL`() {
        val precondition = migration.indexOf(
            "CALL assert_departure_alarm_fire_evidence_preconditions()",
        )
        val ddl = migration.indexOf("CREATE TABLE departure_alarm_fire_events")

        listOf(
            "version = '2026-07-31-push-eta-trust-v1'",
            "version = '2026-08-01-departure-alarm-fire-evidence-v1'",
            "table_name = 'departure_alarm_sync_state'",
            "table_name = 'departure_alarm_fire_events'",
        ).forEach { guard ->
            assertThat(migration.indexOf(guard))
                .describedAs("precondition: $guard")
                .isGreaterThanOrEqualTo(0)
                .isLessThan(precondition)
        }
        assertThat(precondition).isLessThan(ddl)
        assertThat(migration).contains("partial or applied schema requires inspection")
    }

    @Test
    fun `evidence shape uniqueness indexes and marker are post-verified`() {
        val postcondition = migration.indexOf(
            "CALL assert_departure_alarm_fire_evidence_postconditions()",
        )
        val marker = migration.indexOf(
            "INSERT INTO application_schema_migrations(version, description, applied_at)",
        )

        assertThat(migration).contains(
            ") <> 16 THEN",
            "member_id,device_fingerprint,alarm_id,generation,scheduled_for",
            "server_recorded_at,id",
            "schedule_id,server_recorded_at",
            ") <> 6 THEN",
            "desired_generation_at_receipt BETWEEN generation",
            "timing_basis IN ('EXACT_CALLBACK', 'OBSERVED_ALERTING', 'INFERRED_OS_DELIVERY')",
        )
        assertThat(marker).isGreaterThan(postcondition)
    }

    @Test
    fun `production startup requires the evidence migration marker`() {
        assertThat(ProductionSchemaVersionGuard.REQUIRED_SCHEMA_VERSIONS)
            .contains(ProductionSchemaVersionGuard.DEPARTURE_ALARM_FIRE_EVIDENCE_SCHEMA_VERSION)
        assertThat(ProductionSchemaVersionGuard.DEPARTURE_ALARM_FIRE_EVIDENCE_SCHEMA_VERSION)
            .isEqualTo("2026-08-01-departure-alarm-fire-evidence-v1")
    }
}
