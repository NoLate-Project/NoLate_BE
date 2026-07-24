package com.noLate.schedule.controller

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.schedule.application.service.ScheduleCalendarService
import com.noLate.schedule.application.service.ScheduleShareService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

@ExtendWith(MockitoExtension::class)
class ScheduleCalendarSessionFenceControllerTest {

    @Mock
    lateinit var calendarService: ScheduleCalendarService

    @Mock
    lateinit var shareService: ScheduleShareService

    @Test
    fun `calendar mutation forwards signed session generation`() {
        val controller = ScheduleCalendarController(calendarService, shareService)
        val principal = MemberPrincipal(
            id = 7L,
            email = "actor@example.com",
            name = "actor",
            accessTokenSessionGeneration = 11L,
        )

        controller.archiveCalendar(principal, 22L)

        verify(calendarService).archiveCalendar(
            ownerMemberId = 7L,
            calendarId = 22L,
            presentedSessionGeneration = 11L,
        )
    }

    @Test
    fun `calendar mutation rejects an access principal without session generation`() {
        val controller = ScheduleCalendarController(calendarService, shareService)
        val legacyPrincipal = MemberPrincipal(
            id = 7L,
            email = "actor@example.com",
            name = "actor",
        )

        val failure = assertThrows(BusinessException::class.java) {
            controller.archiveCalendar(legacyPrincipal, 22L)
        }

        assertEquals(ErrorCode.INVALID_TOKEN, failure.errorCode)
        verifyNoInteractions(calendarService, shareService)
    }
}
