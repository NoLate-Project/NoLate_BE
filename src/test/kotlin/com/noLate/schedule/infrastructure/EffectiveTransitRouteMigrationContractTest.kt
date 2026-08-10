package com.noLate.schedule.infrastructure

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class EffectiveTransitRouteMigrationContractTest {
    private val migrationPath =
        Path.of("docs/schedule/migrations/2026-08-10-effective-transit-route.sql")

    @Test
    fun `migration adds nullable longtext snapshot with fail closed guards`() {
        val sql = Files.readString(migrationPath)

        assertTrue(sql.contains("column_name = 'last_effective_transit_route_json'"))
        assertTrue(sql.contains("ADD COLUMN last_effective_transit_route_json LONGTEXT NULL"))
        assertTrue(sql.contains("data_type = 'longtext'"))
        assertTrue(sql.contains("column_name = 'last_eta_refresh_due_at'"))
        assertTrue(sql.contains("ADD COLUMN last_eta_refresh_due_at DATETIME(6) NULL"))
        assertTrue(sql.contains("datetime_precision = 6"))
        assertTrue(sql.contains("ADD COLUMN semantic_catch_up_pending BOOLEAN NOT NULL DEFAULT FALSE"))
        assertTrue(sql.contains("semantic catch-up contract is absent"))
        assertTrue(sql.contains("UPDATE schedule_push_job"))
        assertTrue(sql.contains("next_check_at = DATE_ADD(last_checked_at, INTERVAL 1 SECOND)"))
        assertTrue(sql.contains("semantic catch-up backfill is incomplete"))
        assertTrue(sql.contains("2026-08-10-effective-transit-route-v1"))
        assertTrue(sql.contains("partial or applied schema requires inspection"))
    }
}
