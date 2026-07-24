package com.noLate.schedule.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class EtaProvenanceMigrationContractTest {
    private val migrationPath =
        Path.of("docs/schedule/migrations/2026-07-24-eta-provenance.sql")

    @Test
    fun `migration은 부분 적용 상태를 각 컬럼별 guard로 복구하고 최종 개수를 검증한다`() {
        val sql = Files.readString(migrationPath)
        val columns = listOf(
            "last_live_fetched_at",
            "last_eta_source",
            "last_eta_stale",
            "last_eta_failure_reason",
            "last_traffic_change_minutes",
            "last_changed_at",
        )

        columns.forEach { column ->
            assertTrue(
                sql.contains("AND column_name = '$column'"),
                "$column existence guard가 필요합니다.",
            )
            assertTrue(
                sql.contains("ADD COLUMN $column"),
                "$column 독립 ALTER가 필요합니다.",
            )
        }
        assertEquals(6, Regex("SET @eta_column_exists :=").findAll(sql).count())
        assertEquals(6, Regex("PREPARE eta_stmt FROM @eta_ddl").findAll(sql).count())
        assertEquals(6, Regex("DEALLOCATE PREPARE eta_stmt").findAll(sql).count())
        assertTrue(sql.contains("SELECT COUNT(*) AS expected_count"))
    }
}
