package com.noLate.schedule.controller

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.schedule.application.useCase.ScheduleUseCase
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleImportProvider
import com.noLate.schedule.domain.ScheduleImportResultDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

class ScheduleCreationSessionFenceControllerTest {
    private val scheduleUseCase = mock<ScheduleUseCase>()
    private val controller = ScheduleController(scheduleUseCase)

    @Test
    fun `add forwards the signed access session generation to the mutation fence`() {
        val principal = principal(memberId = 41L, sessionGeneration = 17L)
        val request = scheduleRequest("manual")
        whenever(scheduleUseCase.addSchedule(eq(41L), any(), eq(17L)))
            .thenAnswer { invocation -> invocation.getArgument(1) }

        controller.addSchedule(principal, request)

        verify(scheduleUseCase).addSchedule(
            memberId = eq(41L),
            scheduleDto = eq(request.toDto()),
            presentedSessionGeneration = eq(17L),
        )
    }

    @Test
    fun `import forwards the same signed generation and immutable import identity`() {
        val principal = principal(memberId = 42L, sessionGeneration = 23L)
        val request = ImportCalendarScheduleRequest(
            schedule = scheduleRequest("import"),
            source = CalendarImportSourceRequest(
                provider = ScheduleImportProvider.APPLE_DEVICE,
                calendarId = "calendar",
                eventId = "event",
                occurrenceStartAt = "2099-07-24T05:00:00Z",
            ),
        )
        whenever(scheduleUseCase.importSchedule(eq(42L), any(), any(), eq(23L)))
            .thenAnswer { invocation ->
                ScheduleImportResultDto(
                    schedule = invocation.getArgument(1),
                    created = true,
                )
            }

        controller.importSchedule(principal, request)

        verify(scheduleUseCase).importSchedule(
            memberId = eq(42L),
            scheduleDto = eq(request.schedule.toDto()),
            source = eq(request.source.toDomain()),
            presentedSessionGeneration = eq(23L),
        )
    }

    @Test
    fun `update and delete forward the signed generation to the edit fence`() {
        val principal = principal(memberId = 44L, sessionGeneration = 29L)
        val request = updateRequest("update")
        whenever(scheduleUseCase.updateSchedule(eq(44L), eq(101L), any(), eq(29L)))
            .thenAnswer { invocation -> invocation.getArgument(2) }

        controller.updateSchedule(principal, 101L, request)
        controller.deleteSchedule(principal, 101L)

        verify(scheduleUseCase).updateSchedule(
            memberId = eq(44L),
            scheduleId = eq(101L),
            scheduleDto = eq(request.toDto()),
            presentedSessionGeneration = eq(29L),
        )
        verify(scheduleUseCase).deleteSchedule(
            memberId = eq(44L),
            scheduleId = eq(101L),
            presentedSessionGeneration = eq(29L),
        )
    }

    @Test
    fun `legacy principal without a signed generation cannot reach schedule mutation`() {
        val principal = MemberPrincipal(
            id = 43L,
            email = "legacy@example.com",
            name = "legacy",
            accessTokenIssuedAt = Instant.parse("2026-07-24T00:00:00Z"),
            accessTokenSessionGeneration = null,
        )

        val addFailure = assertThrows<BusinessException> {
            controller.addSchedule(principal, scheduleRequest("legacy-add"))
        }
        val importFailure = assertThrows<BusinessException> {
            controller.importSchedule(
                principal,
                ImportCalendarScheduleRequest(
                    schedule = scheduleRequest("legacy-import"),
                    source = CalendarImportSourceRequest(
                        provider = ScheduleImportProvider.GOOGLE,
                        calendarId = "calendar",
                        eventId = "event",
                        occurrenceStartAt = "2099-07-24T05:00:00Z",
                    ),
                ),
            )
        }
        val updateFailure = assertThrows<BusinessException> {
            controller.updateSchedule(principal, 101L, updateRequest("legacy-update"))
        }
        val deleteFailure = assertThrows<BusinessException> {
            controller.deleteSchedule(principal, 101L)
        }

        assertEquals(ErrorCode.INVALID_TOKEN, addFailure.errorCode)
        assertEquals(ErrorCode.INVALID_TOKEN, importFailure.errorCode)
        assertEquals(ErrorCode.INVALID_TOKEN, updateFailure.errorCode)
        assertEquals(ErrorCode.INVALID_TOKEN, deleteFailure.errorCode)
        verify(scheduleUseCase, never()).addSchedule(any(), any(), any())
        verify(scheduleUseCase, never()).importSchedule(any(), any(), any(), any())
        verify(scheduleUseCase, never()).updateSchedule(any(), any(), any(), any())
        verify(scheduleUseCase, never()).deleteSchedule(any(), any(), any())
    }

    private fun principal(
        memberId: Long,
        sessionGeneration: Long,
    ): MemberPrincipal =
        MemberPrincipal(
            id = memberId,
            email = "member-$memberId@example.com",
            name = "member",
            accessTokenIssuedAt = Instant.parse("2026-07-24T00:00:00Z"),
            accessTokenSessionGeneration = sessionGeneration,
        )

    private fun scheduleRequest(title: String): AddScheduleRequest =
        AddScheduleRequest(
            title = title,
            startAt = "2099-07-24T05:00:00Z",
            endAt = "2099-07-24T06:00:00Z",
            category = ScheduleCategoryDto(
                id = "1",
                title = "업무",
                color = "#123456",
            ),
            notificationEnabled = false,
        )

    private fun updateRequest(title: String): UpdateScheduleRequest =
        UpdateScheduleRequest(
            title = title,
            startAt = "2099-07-24T05:00:00Z",
            endAt = "2099-07-24T06:00:00Z",
            category = ScheduleCategoryDto(
                id = "1",
                title = "업무",
                color = "#123456",
            ),
            notificationEnabled = false,
        )
}
