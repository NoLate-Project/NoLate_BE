package com.noLate.schedule.domain

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ScheduleAlertModeContractTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `older schedule requests default to standard alert mode`() {
        val request = mapper.readValue(
            """
            {
              "title": "회의",
              "startAt": "2026-07-29T03:00:00Z",
              "category": {"id": "1", "title": "업무", "color": "#000000"},
              "notificationEnabled": true
            }
            """.trimIndent(),
            ScheduleDto::class.java,
        )

        assertThat(request.alertMode).isNull()
        assertThat(request.toEntity(1L).route?.alertMode).isEqualTo(ScheduleAlertMode.STANDARD)
    }

    @Test
    fun `alarm mode survives schedule entity round trip`() {
        val dto = ScheduleDto(
            title = "회의",
            startAt = "2026-07-29T03:00:00Z",
            category = ScheduleCategoryDto(id = "1", title = "업무", color = "#000000"),
            alertMode = ScheduleAlertMode.ALARM,
        )

        val entity = dto.toEntity(1L)
        val result = entity.toDto(mapper)

        assertThat(entity.route).isNotNull
        assertThat(result.alertMode).isEqualTo(ScheduleAlertMode.ALARM)
    }

    @Test
    fun `production migration verifies both columns before inserting marker`() {
        val migration = Files.readString(
            Path.of("docs/schedule/migrations/2026-07-29-departure-alarm-mode.sql"),
        )
        val firstAlter = migration.indexOf("ALTER TABLE schedule_routes")
        val secondAlter = migration.indexOf("ALTER TABLE schedule_travel_plans")
        val postcondition = migration.indexOf("CALL assert_departure_alarm_mode_postconditions()")
        val marker = migration.indexOf(
            "INSERT INTO application_schema_migrations(version, description, applied_at)",
        )

        assertThat(firstAlter).isGreaterThanOrEqualTo(0)
        assertThat(secondAlter).isGreaterThan(firstAlter)
        assertThat(postcondition).isGreaterThan(secondAlter)
        assertThat(marker).isGreaterThan(postcondition)
        assertThat(migration).contains("DEFAULT 'STANDARD'")
        assertThat(migration).contains("2026-07-29-departure-alarm-mode-v1")
    }
}
