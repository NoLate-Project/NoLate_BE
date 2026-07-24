package com.noLate.schedule.application.cache

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConfigurationProperties("schedule.calendar-cache")
class ScheduleCalendarCacheProperties {
    var enabled: Boolean = true
    var ttl: Duration = Duration.ofMinutes(15)
    var revisionTtl: Duration = Duration.ofDays(7)
}
