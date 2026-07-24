package com.noLate.routehistory.controller

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import com.noLate.routehistory.application.service.RecentRoutePlaceService
import com.noLate.routehistory.domain.RecentRoutePlaceDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class RecentRoutePlaceControllerTest {
    @Mock
    lateinit var service: RecentRoutePlaceService

    private val principal = MemberPrincipal(
        id = 7L,
        email = "member@nolate.test",
        name = "member",
        accessTokenSessionGeneration = 4L,
    )

    @Test
    fun `recent route mutations forward the signed access session generation`() {
        val expected = RecentRoutePlaceDto(
            id = 19L,
            label = "회사",
            lat = 37.5,
            lng = 127.0,
        )
        whenever(
            service.saveRecentPlace(
                memberId = 7L,
                presentedSessionGeneration = 4L,
                label = "회사",
                placeName = "NoLate",
                address = "서울",
                lat = 37.5,
                lng = 127.0,
                provider = "TEST",
                providerPlaceId = "office",
            )
        ).thenReturn(expected)
        val controller = RecentRoutePlaceController(service)

        val response = controller.saveRecentPlace(
            principal = principal,
            request = SaveRecentRoutePlaceRequest(
                label = "회사",
                placeName = "NoLate",
                address = "서울",
                lat = 37.5,
                lng = 127.0,
                provider = "TEST",
                providerPlaceId = "office",
            ),
        )
        controller.deleteRecentPlace(principal, recentPlaceId = 19L)

        assertSame(expected, response.data)
        verify(service).saveRecentPlace(
            memberId = 7L,
            presentedSessionGeneration = 4L,
            label = "회사",
            placeName = "NoLate",
            address = "서울",
            lat = 37.5,
            lng = 127.0,
            provider = "TEST",
            providerPlaceId = "office",
        )
        verify(service).deleteRecentPlace(
            memberId = 7L,
            recentPlaceId = 19L,
            presentedSessionGeneration = 4L,
        )
    }

    @Test
    fun `recent route mutation rejects a principal without a signed session generation`() {
        val legacyPrincipal = MemberPrincipal(
            id = 7L,
            email = "legacy@nolate.test",
            name = "legacy",
        )

        val exception = assertThrows<BusinessException> {
            RecentRoutePlaceController(service).deleteRecentPlace(
                principal = legacyPrincipal,
                recentPlaceId = 19L,
            )
        }

        assertEquals(ErrorCode.INVALID_TOKEN, exception.errorCode)
        verifyNoInteractions(service)
    }
}
