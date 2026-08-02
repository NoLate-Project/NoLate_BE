package com.noLate.schedule.controller

import com.noLate.global.security.MemberPrincipal
import com.noLate.schedule.application.useCase.ScheduleUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ScheduleSearchControllerUnitTest {
    private val scheduleUseCase = mock<ScheduleUseCase>()
    private val controller = ScheduleController(scheduleUseCase)
    private val principal = MemberPrincipal(
        id = 7L,
        email = "member@example.com",
        name = "member",
    )

    @Test
    fun `search forwards the optional result limit`() {
        whenever(
            scheduleUseCase.searchScheduleList(
                memberId = 7L,
                keyword = "회의",
                categoryId = "12",
                startAt = "2026-08-01T00:00:00Z",
                endAt = "2026-08-31T23:59:59Z",
                limit = 35,
            )
        ).thenReturn(emptyList())

        val response = controller.searchScheduleList(
            principal = principal,
            keyword = "회의",
            categoryId = "12",
            startAt = "2026-08-01T00:00:00Z",
            endAt = "2026-08-31T23:59:59Z",
            limit = 35,
        )

        assertEquals(emptyList<Any>(), response.data)
        verify(scheduleUseCase).searchScheduleList(
            memberId = 7L,
            keyword = "회의",
            categoryId = "12",
            startAt = "2026-08-01T00:00:00Z",
            endAt = "2026-08-31T23:59:59Z",
            limit = 35,
        )
    }
}
