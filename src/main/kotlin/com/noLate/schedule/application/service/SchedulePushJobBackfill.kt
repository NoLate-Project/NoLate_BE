package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.schedule.infrastructure.ScheduleRepository
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class SchedulePushJobBackfill(
    private val scheduleRepository: ScheduleRepository,
    private val schedulePushJobService: SchedulePushJobService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun registerMissingJobs() {
        val schedules = scheduleRepository.findNotificationEnabledWithoutPushJob(Instant.now())

        schedules.forEach { schedule ->
            schedulePushJobService.registerFromScheduleDto(
                memberId = schedule.memberId,
                scheduleDto = schedule.toDto(objectMapper),
            )
        }

        if (schedules.isNotEmpty()) {
            log.info("Recovered missing schedule push jobs. count={}", schedules.size)
        }
    }
}
