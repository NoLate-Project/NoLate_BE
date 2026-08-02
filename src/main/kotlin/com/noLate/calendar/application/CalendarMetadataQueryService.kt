package com.noLate.calendar.application

import com.noLate.calendar.application.cache.CalendarMetadataCacheLoad
import com.noLate.calendar.application.cache.CalendarMetadataCacheService
import com.noLate.calendar.domain.CalendarDayDto
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class CalendarMetadataQueryService(
    private val metadataService: CalendarMetadataService,
    private val cacheService: CalendarMetadataCacheService,
) {
    fun getDays(startDate: LocalDate, endDate: LocalDate): List<CalendarDayDto> {
        val refreshPlans = mutableListOf<CalendarMetadataRefreshPlan>()
        val days = cacheService.getOrLoadSnapshot(
            startDate = startDate,
            endDate = endDate,
        ) { loadStart, loadEnd ->
            metadataService.loadCurrentSnapshot(loadStart, loadEnd).also { snapshot ->
                refreshPlans += snapshot.refreshPlans
            }.toCacheLoad()
        }

        // Schedule only after the cold Redis fill has returned. A fast KASI response therefore
        // cannot refill Redis first and then be overwritten by the request's older DB snapshot.
        metadataService.refreshCacheAsync(refreshPlans) { refreshedMonths ->
            cacheService.refillMonths(refreshedMonths) { loadStart, loadEnd ->
                metadataService.loadCurrentSnapshot(loadStart, loadEnd).toCacheLoad()
            }
        }
        return days
    }

    private fun CalendarMetadataSnapshot.toCacheLoad() = CalendarMetadataCacheLoad(
        days = days,
        cacheableMonths = cacheableMonths,
    )
}
