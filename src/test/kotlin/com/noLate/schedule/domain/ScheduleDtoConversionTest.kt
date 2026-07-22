package com.noLate.schedule.domain

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ScheduleDtoConversionTest {

    @Test
    fun `entity conversion preserves shared calendar metadata`() {
        val schedule = Schedule(
            id = 41L,
            memberId = 7L,
            calendarId = 13L,
            scheduleType = ScheduleType.ROUTE,
            calendarContentModeOverride = ScheduleShareContentMode.SCHEDULE_ONLY,
            title = "Shared schedule",
        )
        schedule.updateCategorySnapshot(
            categoryId = "3",
            title = "Work",
            color = "#2F80FF",
        )

        val dto = schedule.toDto(ObjectMapper())

        assertThat(dto.calendarId).isEqualTo(13L)
        assertThat(dto.scheduleType).isEqualTo(ScheduleType.ROUTE)
        assertThat(dto.calendarContentModeOverride).isEqualTo(ScheduleShareContentMode.SCHEDULE_ONLY)
    }
}
