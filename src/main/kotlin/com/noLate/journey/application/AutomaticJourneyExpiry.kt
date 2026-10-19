package com.noLate.journey.application

import com.noLate.journey.domain.AutomaticJourneyState
import com.noLate.journey.infrastructure.AutomaticJourneyPreparationRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

/** Server-owned TTL, independent of app/JS/auth refresh. No push or departure side effects. */
@Service
class AutomaticJourneyExpiry(
    private val repository: AutomaticJourneyPreparationRepository,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${journey.guidance.enabled:false}") private val guidanceEnabled: Boolean = false,
    @Value("\${journey.automatic.enabled:false}") private val automaticEnabled: Boolean = false,
    @Value("\${journey.automatic.expiry-enabled:false}") private val enabled: Boolean = false,
) {
    @Scheduled(fixedDelayString = "\${journey.automatic.expiry-delay-ms:10000}")
    @Transactional
    fun expireElapsedSessions(): Int {
        if (!enabled || !guidanceEnabled || !automaticEnabled) return 0
        return repository.expireElapsed(clock.instant().truncatedTo(ChronoUnit.MILLIS),
            listOf(AutomaticJourneyState.ARMED, AutomaticJourneyState.ACTIVE), AutomaticJourneyState.EXPIRED)
    }
}
