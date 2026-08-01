package com.noLate.schedule.controller

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.schedule.application.service.ScheduleEtaAccuracyObservationDto
import com.noLate.schedule.application.service.ScheduleEtaAccuracyService
import com.noLate.schedule.application.service.ScheduleEtaObservationEngagementDto
import com.noLate.schedule.application.service.ScheduleEtaObservationEngagementEvent
import com.noLate.schedule.domain.ScheduleArrivalObservationSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class ScheduleEtaAccuracyControllerUnitTest {
    @Mock
    lateinit var service: ScheduleEtaAccuracyService

    @Test
    fun `arrival forwards client callback timestamp instead of replacing it with server time`() {
        val arrivedAt = Instant.parse("2026-07-31T04:00:00Z")
        val observation = mock<ScheduleEtaAccuracyObservationDto>()
        whenever(
            service.recordArrival(
                7L,
                41L,
                arrivedAt,
                ScheduleArrivalObservationSource.USER_ADJUSTED,
                60,
                300,
                "1.2.0",
                "42",
            )
        ).thenReturn(observation)
        val controller = ScheduleEtaAccuracyController(service)

        val response = controller.recordArrival(
            MemberPrincipal(7L, "member@example.com", "member"),
            41L,
            ScheduleArrivalObservationRequest(
                arrivedAt = arrivedAt,
                observationSource = ScheduleArrivalObservationSource.USER_ADJUSTED,
                precisionSeconds = 60,
                adjustmentSeconds = 300,
                clientAppVersion = "1.2.0",
                clientBuildVersion = "42",
            ),
        )

        assertEquals(observation, response.data)
        verify(service).recordArrival(
            7L,
            41L,
            arrivedAt,
            ScheduleArrivalObservationSource.USER_ADJUSTED,
            60,
            300,
            "1.2.0",
            "42",
        )
    }

    @Test
    fun `engagement forwards bounded event and client UX cohort`() {
        val engagement = mock<ScheduleEtaObservationEngagementDto>()
        whenever(
            service.recordEngagement(
                7L,
                41L,
                ScheduleEtaObservationEngagementEvent.PROMPT_OPENED,
                "1.2.0",
                "42",
                "arrival-card-v1",
            )
        ).thenReturn(engagement)
        val controller = ScheduleEtaAccuracyController(service)

        val response = controller.recordEngagement(
            MemberPrincipal(7L, "member@example.com", "member"),
            41L,
            ScheduleEtaObservationEngagementRequest(
                event = ScheduleEtaObservationEngagementEvent.PROMPT_OPENED,
                clientAppVersion = "1.2.0",
                clientBuildVersion = "42",
                uxVariant = "arrival-card-v1",
            ),
        )

        assertEquals(engagement, response.data)
        verify(service).recordEngagement(
            7L,
            41L,
            ScheduleEtaObservationEngagementEvent.PROMPT_OPENED,
            "1.2.0",
            "42",
            "arrival-card-v1",
        )
    }

    @Test
    fun `arrival requires an authenticated principal`() {
        val controller = ScheduleEtaAccuracyController(service)

        val failure = assertThrows<BusinessException> {
            controller.recordArrival(
                null,
                41L,
                ScheduleArrivalObservationRequest(
                    arrivedAt = Instant.parse("2026-07-31T04:00:00Z"),
                    observationSource = ScheduleArrivalObservationSource.USER_NOW,
                    precisionSeconds = 30,
                ),
            )
        }

        assertEquals(ErrorCode.UNAUTHORIZED, failure.errorCode)
    }
}
