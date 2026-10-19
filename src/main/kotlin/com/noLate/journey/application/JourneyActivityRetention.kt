package com.noLate.journey.application

import com.noLate.journey.infrastructure.JourneyActivityLaneRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** Bounded credential retention, including rejected/unknown tombstones. Never extend on UPDATE. */
@Service
class JourneyActivityRetention(private val lanes: JourneyActivityLaneRepository, private val clock: Clock = Clock.systemUTC()) {
    @Scheduled(fixedDelayString = "\${journey.activity.retention-delay-ms:3600000}")
    @Transactional
    fun clean() = lanes.deleteAllByRetentionExpiresAtBefore(clock.instant())
}
