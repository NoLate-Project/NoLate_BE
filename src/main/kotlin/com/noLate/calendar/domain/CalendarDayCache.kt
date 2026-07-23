package com.noLate.calendar.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "calendar_day_cache")
class CalendarDayCache(
    @Id
    @Column(name = "solar_date", nullable = false)
    var date: LocalDate = LocalDate.of(1970, 1, 1),

    @Column(name = "lunar_year")
    var lunarYear: Int? = null,

    @Column(name = "lunar_month")
    var lunarMonth: Int? = null,

    @Column(name = "lunar_day")
    var lunarDay: Int? = null,

    @Column(name = "leap_month")
    var leapMonth: Boolean? = null,

    @Column(name = "lunar_synced_at")
    var lunarSyncedAt: LocalDateTime? = null,

    @Column(name = "holidays_synced_at")
    var holidaysSyncedAt: LocalDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.of(1970, 1, 1, 0, 0),
) {
    fun updateLunar(
        year: Int,
        month: Int,
        day: Int,
        leapMonth: Boolean,
        syncedAt: LocalDateTime,
    ) {
        lunarYear = year
        lunarMonth = month
        lunarDay = day
        this.leapMonth = leapMonth
        lunarSyncedAt = syncedAt
        updatedAt = syncedAt
    }

    fun markHolidaysSynced(syncedAt: LocalDateTime) {
        holidaysSyncedAt = syncedAt
        updatedAt = syncedAt
    }
}
