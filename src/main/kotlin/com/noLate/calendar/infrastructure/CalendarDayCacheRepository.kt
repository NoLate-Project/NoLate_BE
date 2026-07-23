package com.noLate.calendar.infrastructure

import com.noLate.calendar.domain.CalendarDayCache
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface CalendarDayCacheRepository : JpaRepository<CalendarDayCache, LocalDate> {
    fun findAllByDateBetweenOrderByDateAsc(
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<CalendarDayCache>
}
