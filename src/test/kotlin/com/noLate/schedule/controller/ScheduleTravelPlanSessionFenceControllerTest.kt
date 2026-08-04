package com.noLate.schedule.controller

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.schedule.application.useCase.ScheduleTravelPlanUseCase
import com.noLate.schedule.domain.SchedulePlaceDto
import com.noLate.schedule.domain.ScheduleTravelPlanDto
import com.noLate.schedule.domain.ScheduleTravelPlanStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ScheduleTravelPlanSessionFenceControllerTest {
    private val useCase = mock<ScheduleTravelPlanUseCase>()
    private val controller = ScheduleTravelPlanController(useCase)

    @Test
    fun `travel plan mutation forwards the signed session generation`() {
        val principal = MemberPrincipal(
            id = 17L,
            email = "member@example.com",
            name = "member",
            accessTokenSessionGeneration = 8L,
        )
        val request = ScheduleTravelPlanUpsertRequest(notificationEnabled = false)
        val saved = ScheduleTravelPlanDto(
            scheduleId = 31L,
            memberId = 17L,
            status = ScheduleTravelPlanStatus.NOT_CONFIGURED,
            notificationEnabled = false,
        )
        whenever(useCase.upsertMyTravelPlan(eq(17L), eq(31L), any(), eq(8L)))
            .thenReturn(saved)

        val response = controller.upsertMyTravelPlan(principal, 31L, request)

        assertEquals(saved, response.data)
        verify(useCase).upsertMyTravelPlan(
            memberId = eq(17L),
            scheduleId = eq(31L),
            command = eq(request.toCommand()),
            presentedSessionGeneration = eq(8L),
        )
    }

    @Test
    fun `travel plan mutation rejects a principal without a signed generation`() {
        val principal = MemberPrincipal(
            id = 17L,
            email = "legacy@example.com",
            name = "legacy",
        )

        val failure = assertThrows<BusinessException> {
            controller.upsertMyTravelPlan(
                principal,
                31L,
                ScheduleTravelPlanUpsertRequest(notificationEnabled = false),
            )
        }

        assertEquals(ErrorCode.INVALID_TOKEN, failure.errorCode)
        verify(useCase, never()).upsertMyTravelPlan(any(), any(), any(), any())
    }

    @Test
    fun `travel plan request maps optional destination used for common coordinate supplement`() {
        val request = ScheduleTravelPlanUpsertRequest(
            destination = SchedulePlaceDto(
                name = "강남역",
                address = "서울특별시 강남구 강남대로 지하 396",
                lat = 37.497,
                lng = 127.027,
            ),
        )

        val command = request.toCommand()

        assertEquals("강남역", command.destinationName)
        assertEquals("서울특별시 강남구 강남대로 지하 396", command.destinationAddress)
        assertEquals(37.497, command.destinationLat)
        assertEquals(127.027, command.destinationLng)
    }
}
