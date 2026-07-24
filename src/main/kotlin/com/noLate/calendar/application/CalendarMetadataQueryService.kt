package com.noLate.calendar.application

import com.noLate.calendar.application.cache.CalendarMetadataCacheService
import com.noLate.calendar.domain.CalendarDayDto
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class CalendarMetadataQueryService(
    private val metadataService: CalendarMetadataService,
    private val cacheService: CalendarMetadataCacheService,
) {
    fun getDays(startDate: LocalDate, endDate: LocalDate): List<CalendarDayDto> =
        cacheService.getOrLoad(
            startDate = startDate,
            endDate = endDate,
            loader = metadataService::getDays,
        )
}
