package com.noLate.schedule.application.service

import com.noLate.schedule.domain.QuickScheduleClientPlatform
import com.noLate.schedule.domain.QuickScheduleParseFeedbackDto
import com.noLate.schedule.domain.QuickScheduleParseTelemetry
import com.noLate.schedule.domain.ScheduleParseDto
import com.noLate.schedule.domain.ScheduleParseInputType
import com.noLate.schedule.infrastructure.QuickScheduleParseTelemetryRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID

const val QUICK_SCHEDULE_CONFIDENCE_VERSION = "quick-schedule-v1"

@Service
class QuickScheduleReliabilityTelemetryService(
    private val repository: QuickScheduleParseTelemetryRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 신뢰도 보정에 필요한 숫자와 bounded enum만 기록한다. 원문이나 추출된 개인정보는 받지 않는다.
     */
    fun recordParse(
        memberId: Long,
        inputType: ScheduleParseInputType,
        clientPlatform: QuickScheduleClientPlatform,
        result: ScheduleParseDto,
    ): ScheduleParseDto {
        val analysisId = UUID.randomUUID().toString()
        val now = clock.instant()
        val confidence = result.confidence
        return try {
            repository.save(
                QuickScheduleParseTelemetry(
                    analysisId = analysisId,
                    memberId = memberId,
                    inputType = inputType,
                    clientPlatform = clientPlatform,
                    parseSource = result.parseSource,
                    confidenceLevel = confidence?.level,
                    overallConfidence = confidence?.overall,
                    recognitionConfidence = confidence?.recognition,
                    dateConfidence = confidence?.fields?.date,
                    timeConfidence = confidence?.fields?.time,
                    destinationConfidence = confidence?.fields?.destination,
                    needsReview = result.needsReview,
                    confidenceVersion = QUICK_SCHEDULE_CONFIDENCE_VERSION,
                    createdAt = now,
                    expiresAt = now.plus(RETENTION),
                )
            )
            result.copy(
                analysisId = analysisId,
                confidenceVersion = QUICK_SCHEDULE_CONFIDENCE_VERSION,
            )
        } catch (error: RuntimeException) {
            // 품질 측정 장애가 일정 분석 자체를 막아서는 안 된다.
            log.warn("Quick schedule parse telemetry write failed", error)
            result.copy(confidenceVersion = QUICK_SCHEDULE_CONFIDENCE_VERSION)
        }
    }

    @Transactional
    fun recordFeedback(
        memberId: Long,
        analysisId: String,
        feedback: QuickScheduleParseFeedbackDto,
    ) {
        val normalizedId = analysisId.trim()
        if (normalizedId.length != UUID_TEXT_LENGTH) return
        val telemetry = repository.findByAnalysisIdAndMemberId(normalizedId, memberId) ?: return
        telemetry.applyFeedback(feedback, clock.instant())
    }

    @Transactional
    fun deleteForMember(memberId: Long) {
        repository.deleteAllByMemberId(memberId)
    }

    @Scheduled(cron = "0 23 4 * * *", zone = "UTC")
    @Transactional
    fun deleteExpired() {
        val deleted = repository.deleteAllByExpiresAtBefore(clock.instant())
        if (deleted > 0) log.info("Deleted {} expired quick schedule telemetry rows", deleted)
    }

    private companion object {
        val RETENTION: Duration = Duration.ofDays(90)
        const val UUID_TEXT_LENGTH = 36
    }
}
