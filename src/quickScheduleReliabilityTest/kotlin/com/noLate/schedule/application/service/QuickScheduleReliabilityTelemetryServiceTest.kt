package com.noLate.schedule.application.service

import com.noLate.schedule.domain.QuickScheduleClientPlatform
import com.noLate.schedule.domain.QuickScheduleFeedbackOutcome
import com.noLate.schedule.domain.QuickScheduleParseFeedbackDto
import com.noLate.schedule.domain.QuickScheduleParseTelemetry
import com.noLate.schedule.domain.QuickScheduleVerificationSignal
import com.noLate.schedule.domain.ScheduleFieldConfidenceDto
import com.noLate.schedule.domain.ScheduleParseConfidenceDto
import com.noLate.schedule.domain.ScheduleParseConfidenceLevel
import com.noLate.schedule.domain.ScheduleParseDto
import com.noLate.schedule.domain.ScheduleParseInputType
import com.noLate.schedule.domain.ScheduleParseSource
import com.noLate.schedule.infrastructure.QuickScheduleParseTelemetryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class QuickScheduleReliabilityTelemetryServiceTest {
    private val repository = mock<QuickScheduleParseTelemetryRepository>()
    private val now = Instant.parse("2026-08-01T00:00:00Z")
    private val service = QuickScheduleReliabilityTelemetryService(
        repository,
        Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `parse telemetry stores only bounded diagnostics and returns opaque id`() {
        whenever(repository.save(any<QuickScheduleParseTelemetry>())).thenAnswer { it.arguments[0] }
        val result = service.recordParse(
            memberId = 7,
            inputType = ScheduleParseInputType.IMAGE_OCR,
            clientPlatform = QuickScheduleClientPlatform.ANDROID,
            result = ScheduleParseDto(
                title = "민감한 제목",
                notes = "민감한 메모",
                parseSource = ScheduleParseSource.RULE,
                confidence = ScheduleParseConfidenceDto(
                    overall = 0.93,
                    level = ScheduleParseConfidenceLevel.HIGH,
                    recognition = 0.91,
                    fields = ScheduleFieldConfidenceDto(0.98, 0.94, 0.91),
                ),
            ),
        )

        val captor = argumentCaptor<QuickScheduleParseTelemetry>()
        verify(repository).save(captor.capture())
        val telemetry = captor.firstValue
        assertNotNull(result.analysisId)
        assertEquals(result.analysisId, telemetry.analysisId)
        assertEquals(QuickScheduleClientPlatform.ANDROID, telemetry.clientPlatform)
        assertEquals(0.93, telemetry.overallConfidence)
        assertEquals(now.plusSeconds(90L * 24 * 60 * 60), telemetry.expiresAt)
        assertEquals(QUICK_SCHEDULE_CONFIDENCE_VERSION, result.confidenceVersion)
        // The entity contract has no text/title/note/place property; only numeric diagnostics above.
    }

    @Test
    fun `feedback is member scoped and records user correction separately from confidence`() {
        val telemetry = telemetry()
        whenever(repository.findByAnalysisIdAndMemberId(telemetry.analysisId, 7))
            .thenReturn(telemetry)

        service.recordFeedback(
            memberId = 7,
            analysisId = telemetry.analysisId,
            feedback = QuickScheduleParseFeedbackDto(
                outcome = QuickScheduleFeedbackOutcome.SAVED,
                date = QuickScheduleVerificationSignal.USER_CONFIRMED,
                destination = QuickScheduleVerificationSignal.USER_CORRECTED,
            ),
        )

        assertEquals(QuickScheduleFeedbackOutcome.SAVED, telemetry.outcome)
        assertEquals(QuickScheduleVerificationSignal.USER_CONFIRMED, telemetry.dateVerification)
        assertEquals(QuickScheduleVerificationSignal.USER_CORRECTED, telemetry.destinationVerification)
        assertEquals(0.93, telemetry.overallConfidence)
        assertEquals(now, telemetry.feedbackAt)
    }

    private fun telemetry() = QuickScheduleParseTelemetry(
        analysisId = "7f34ed5a-623c-4b4e-893f-6750169e49e0",
        memberId = 7,
        inputType = ScheduleParseInputType.IMAGE_OCR,
        clientPlatform = QuickScheduleClientPlatform.ANDROID,
        parseSource = ScheduleParseSource.RULE,
        confidenceLevel = ScheduleParseConfidenceLevel.HIGH,
        overallConfidence = 0.93,
        recognitionConfidence = 0.91,
        dateConfidence = 0.98,
        timeConfidence = 0.94,
        destinationConfidence = 0.91,
        needsReview = false,
        confidenceVersion = QUICK_SCHEDULE_CONFIDENCE_VERSION,
        createdAt = now,
        expiresAt = now.plusSeconds(90L * 24 * 60 * 60),
    )
}
