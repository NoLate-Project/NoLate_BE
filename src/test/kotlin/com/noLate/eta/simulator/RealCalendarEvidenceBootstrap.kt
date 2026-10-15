package com.noLate.eta.simulator

import com.noLate.calendar.application.CalendarCacheWriter
import com.noLate.calendar.infrastructure.KasiCalendarClient
import com.noLate.eta.infrastructure.seoul.SeoulTimetableServiceDay
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/** Test-classpath only. The caller must verify H2 before providing its writer. */
internal class RealCalendarEvidenceBootstrap(
    private val serviceDay: SeoulTimetableServiceDay,
    private val client: KasiCalendarClient,
    private val writer: CalendarCacheWriter,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun prepare(date: LocalDate): Map<String, Any?> {
        val existing = serviceDay.weekTag(date)
        if (existing != null) return mapOf("source" to "EXISTING_VERIFIED_SERVICE_DAY", "date" to date,
            "weekTag" to existing, "additionalKasiCalls" to 0)
        check(client.isAvailable()) { "Actual KASI evidence is required; no weekday guess is allowed" }
        // Fetch once before marking freshness. An API failure or incomplete page must abort;
        // do not turn an unsuccessful response into an empty/ordinary working day.
        val holidays = client.fetchHolidayMonth(YearMonth.from(date))
        val receivedAt = LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"))
        writer.replaceHolidayMonth(YearMonth.from(date), holidays, receivedAt)
        val verified = checkNotNull(serviceDay.weekTag(date)) { "Fetched holiday evidence did not verify the service day" }
        return mapOf("source" to "ACTUAL_KASI_MONTH_FETCH_IN_H2", "date" to date, "weekTag" to verified,
            "receivedAt" to receivedAt, "additionalKasiCalls" to 1,
            "holidayDates" to holidays.map { it.date }.distinct(), "productionDatabaseModified" to false)
    }
}
