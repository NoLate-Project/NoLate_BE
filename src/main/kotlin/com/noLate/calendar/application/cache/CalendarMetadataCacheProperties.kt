package com.noLate.calendar.application.cache

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConfigurationProperties("calendar.metadata-cache")
class CalendarMetadataCacheProperties {
    var enabled: Boolean = true
    var ttl: Duration = Duration.ofHours(24)
}
