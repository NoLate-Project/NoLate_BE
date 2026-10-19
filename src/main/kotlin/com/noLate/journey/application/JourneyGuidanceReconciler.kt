package com.noLate.journey.application

import com.noLate.journey.infrastructure.JourneyGuidanceSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Component
class JourneyGuidanceReconciler(
    private val repository: JourneyGuidanceSessionRepository,
    private val service: JourneyGuidanceService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var cursor = 0L

    @Scheduled(fixedDelayString = "\${journey.guidance.reconcile-delay-ms:30000}")
    fun reconcile() {
        val ids = repository.findActiveIds(cursor, PageRequest.of(0, 100))
        if (ids.isEmpty()) { cursor = 0; return }
        ids.forEach { id ->
            try { service.reconcile(id) } catch (error: Exception) {
                // Do not log route snapshots, instructions, device identifiers, or location data.
                log.warn("Journey reconciliation retry needed: {}", error.javaClass.simpleName)
            }
        }
        cursor = ids.last()
        if (ids.size < 100) cursor = 0
    }
}

@Service
class JourneyGuidanceRetention(
    private val repository: JourneyGuidanceSessionRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Scheduled(fixedDelayString = "\${journey.guidance.retention-delay-ms:3600000}")
    @Transactional
    fun cleanExpiredHistory() {
        repository.deleteAllByEndedAtBefore(Instant.now(clock).minusSeconds(7 * 24 * 60 * 60))
    }
}
