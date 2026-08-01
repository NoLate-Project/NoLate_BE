package com.noLate.schedule.domain

import com.noLate.global.config.ProductionSchemaVersionGuard
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DepartureAlarmSyncMigrationContractTest {
    private val migration = Files.readString(
        Path.of("docs/schedule/migrations/2026-07-29-departure-alarm-sync.sql"),
    )

    @Test
    fun `partial schema and rerun are rejected before the first DDL`() {
        val precondition = migration.indexOf("CALL assert_departure_alarm_sync_preconditions()")
        val firstDdl = migration.indexOf("ALTER TABLE app_notifications")
        val stateTableGuard = migration.indexOf("table_name = 'departure_alarm_sync_state'")
        val visibilityColumnGuard = migration.indexOf("column_name = 'inbox_visible'")
        val markerGuard = migration.indexOf(
            "version = '2026-07-29-departure-alarm-sync-v1'",
        )

        assertThat(stateTableGuard).isGreaterThanOrEqualTo(0).isLessThan(precondition)
        assertThat(visibilityColumnGuard).isGreaterThanOrEqualTo(0).isLessThan(precondition)
        assertThat(markerGuard).isGreaterThanOrEqualTo(0).isLessThan(precondition)
        assertThat(precondition).isLessThan(firstDdl)
        assertThat(migration).contains("partial or already-applied schema requires inspection")
    }

    @Test
    fun `every index name uniqueness and ordered composition is verified before marker`() {
        val postcondition = migration.indexOf("CALL assert_departure_alarm_sync_postconditions()")
        val marker = migration.indexOf(
            "INSERT INTO application_schema_migrations(version, description, applied_at)",
        )

        assertThat(migration).contains("GROUP BY index_name")
        assertThat(migration).contains("MIN(non_unique) = 0")
        assertThat(migration).contains("MIN(non_unique) = 1")
        assertThat(migration).contains(
            "GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'member_id,schedule_id'",
            "GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'alarm_id'",
            "GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'member_id,id'",
            "GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'operation,trigger_at,id'",
            ") <> 4 THEN",
        )
        assertThat(marker).isGreaterThan(postcondition)
    }

    @Test
    fun `production guard requires the verified alarm sync marker`() {
        assertThat(ProductionSchemaVersionGuard.REQUIRED_SCHEMA_VERSIONS)
            .contains(ProductionSchemaVersionGuard.DEPARTURE_ALARM_SYNC_SCHEMA_VERSION)
        assertThat(ProductionSchemaVersionGuard.DEPARTURE_ALARM_SYNC_SCHEMA_VERSION)
            .isEqualTo("2026-07-29-departure-alarm-sync-v1")
    }
}
