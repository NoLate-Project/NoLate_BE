package com.noLate.schedule.domain

import com.noLate.global.config.ProductionSchemaVersionGuard
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DepartureAlarmCancellationReasonMigrationContractTest {
    @Test
    fun `사유 컬럼은 nullable로 추가하고 기존 사유를 추정하지 않으며 marker는 DDL 뒤 기록한다`() {
        val sql = Files.readString(Path.of("docs/schedule/migrations/2026-10-05-departure-alarm-cancellation-reason.sql"))
        assertThat(sql).contains("cancellation_reason VARCHAR(40) NULL", "required tables are absent", "plan v2 marker is absent")
        assertThat(sql).doesNotContain("UPDATE departure_alarm_sync_state")
        assertThat(sql.indexOf("INSERT INTO application_schema_migrations")).isGreaterThan(sql.indexOf("ADD CONSTRAINT"))
        assertThat(sql.indexOf("required tables are absent")).isLessThan(sql.indexOf("ADD COLUMN"))
        assertThat(sql.indexOf("CALL migrate_departure_alarm_cancellation_reason()")).isGreaterThan(sql.indexOf("END//"))
        val schema = Files.readString(Path.of("src/main/resources/schema.sql"))
        assertThat(schema).contains("cancellation_reason VARCHAR(40) NULL", "chk_departure_alarm_cancellation_reason")
        assertThat(ProductionSchemaVersionGuard.REQUIRED_SCHEMA_VERSIONS)
            .contains(ProductionSchemaVersionGuard.DEPARTURE_ALARM_CANCELLATION_REASON_SCHEMA_VERSION)
    }
}
