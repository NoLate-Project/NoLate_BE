package com.noLate.notification.domain

import com.noLate.global.config.ProductionSchemaVersionGuard
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DepartureAlarmScheduleReceiptMigrationContractTest {
    private val migration = Files.readString(
        Path.of("docs/schedule/migrations/2026-08-01-departure-alarm-schedule-receipts.sql"),
    )

    @Test
    fun `receipt migration guards predecessor partial state and rerun before DDL`() {
        val precondition = migration.indexOf("CALL assert_departure_alarm_receipt_preconditions()")
        val ddl = migration.indexOf("CREATE TABLE departure_alarm_schedule_receipts")
        listOf(
            "version = '2026-08-01-departure-alarm-fire-evidence-v1'",
            "version = '2026-08-01-departure-alarm-schedule-receipts-v1'",
            "table_name = 'departure_alarm_schedule_receipts'",
        ).forEach { guard ->
            assertThat(migration.indexOf(guard)).isBetween(0, precondition - 1)
        }
        assertThat(precondition).isLessThan(ddl)
        assertThat(migration).contains("partial or applied schema requires inspection")
    }

    @Test
    fun `receipt denominator shape indexes checks and marker are post-verified`() {
        val postcondition = migration.indexOf("CALL assert_departure_alarm_receipt_postconditions()")
        val marker = migration.indexOf(
            "INSERT INTO application_schema_migrations(version, description, applied_at)",
        )
        assertThat(migration).contains(
            ") <> 22 THEN",
            ") <> 6 THEN",
            "outcome,trigger_at,platform,delivery_mode,server_recorded_at",
            "member_id,device_fingerprint,command_receipt_key",
            "outcome = 'SCHEDULED'",
            "outcome = 'CANCELED'",
            "outcome = 'FAILED'",
        )
        assertThat(marker).isGreaterThan(postcondition)
    }

    @Test
    fun `production startup requires receipt denominator migration marker`() {
        assertThat(ProductionSchemaVersionGuard.REQUIRED_SCHEMA_VERSIONS)
            .contains(ProductionSchemaVersionGuard.DEPARTURE_ALARM_SCHEDULE_RECEIPT_SCHEMA_VERSION)
        assertThat(ProductionSchemaVersionGuard.DEPARTURE_ALARM_SCHEDULE_RECEIPT_SCHEMA_VERSION)
            .isEqualTo("2026-08-01-departure-alarm-schedule-receipts-v1")
    }
}
