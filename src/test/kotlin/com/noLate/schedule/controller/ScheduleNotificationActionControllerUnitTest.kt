package com.noLate.schedule.controller

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.schedule.application.useCase.ScheduleUseCase
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class ScheduleNotificationActionControllerUnitTest {
    @Mock
    lateinit var scheduleUseCase: ScheduleUseCase

    private val sessionGeneration = 9L
    private val principal = MemberPrincipal(
        id = 7L,
        email = "member@example.com",
        name = "member",
        accessTokenSessionGeneration = sessionGeneration,
    )
    private val schedule = ScheduleDto(
        id = 71L,
        ownerMemberId = 7L,
        title = "notification action",
        startAt = "2026-07-24T10:00:00Z",
        category = ScheduleCategoryDto(id = "1", title = "일정", color = "#246BFE"),
    )

    @Test
    fun `depart-now forwards action-specific Idempotency-Key`() {
        val key = "departNow:key:" + "c".repeat(64)
        whenever(scheduleUseCase.markDeparted(7L, 71L, key, sessionGeneration)).thenReturn(schedule)
        val controller = ScheduleController(scheduleUseCase)

        val response = controller.markScheduleDeparted(principal, 71L, key, 7L)

        assertEquals(schedule, response.data)
        verify(scheduleUseCase).markDeparted(7L, 71L, key, sessionGeneration)
    }

    @Test
    fun `snooze forwards key and missing header stays legacy-compatible`() {
        val key = "snooze:key:" + "d".repeat(64)
        val controller = ScheduleController(scheduleUseCase)

        controller.snoozeDepartureReminder(principal, 71L, key, 7L)
        controller.snoozeDepartureReminder(principal, 71L, null)

        verify(scheduleUseCase).snoozeDepartureReminder(7L, 71L, key, sessionGeneration)
        verify(scheduleUseCase).snoozeDepartureReminder(7L, 71L, null, sessionGeneration)
    }

    @Test
    fun `depart-now rejects mismatched notification recipient before mutation`() {
        val controller = ScheduleController(scheduleUseCase)

        val failure = assertThrows<BusinessException> {
            controller.markScheduleDeparted(
                principal,
                71L,
                "departNow:key:" + "a".repeat(64),
                8L,
            )
        }

        assertEquals(ErrorCode.FORBIDDEN, failure.errorCode)
        verifyNoInteractions(scheduleUseCase)
    }

    @Test
    fun `snooze action key requires matching notification recipient before mutation`() {
        val controller = ScheduleController(scheduleUseCase)

        val failure = assertThrows<BusinessException> {
            controller.snoozeDepartureReminder(
                principal,
                71L,
                "snooze:key:" + "b".repeat(64),
                null,
            )
        }

        assertEquals(ErrorCode.FORBIDDEN, failure.errorCode)
        verifyNoInteractions(scheduleUseCase)
    }

    @Test
    fun `notification action rejects a principal without signed session generation`() {
        val legacyPrincipal = MemberPrincipal(7L, "member@example.com", "member")
        val controller = ScheduleController(scheduleUseCase)

        val failure = assertThrows<BusinessException> {
            controller.snoozeDepartureReminder(legacyPrincipal, 71L, null)
        }

        assertEquals(ErrorCode.UNAUTHORIZED, failure.errorCode)
    }
}
