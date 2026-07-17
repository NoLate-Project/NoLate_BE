package com.noLate.favorite.controller

import com.noLate.favorite.application.service.FavoritePlaceService
import com.noLate.favorite.domain.FavoritePlaceDto
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.global.security.MemberPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class FavoritePlaceControllerTest {

    @Mock
    lateinit var favoritePlaceService: FavoritePlaceService

    private val principal = MemberPrincipal(
        id = 7L,
        email = "member@nolate.test",
        name = "tester",
    )

    @Test
    fun `getDefaultOrigin scopes the lookup to the authenticated member`() {
        val expected = defaultOrigin()
        whenever(favoritePlaceService.getDefaultOrigin(7L)).thenReturn(expected)

        val response = controller().getDefaultOrigin(principal)

        assertTrue(response.success)
        assertSame(expected, response.data)
        verify(favoritePlaceService).getDefaultOrigin(7L)
    }

    @Test
    fun `saveDefaultOrigin forwards the authenticated member and place fields`() {
        val expected = defaultOrigin()
        whenever(
            favoritePlaceService.saveDefaultOrigin(
                memberId = eq(7L),
                label = eq("집"),
                placeName = eq("우리 집"),
                address = eq("서울 중구 세종대로 110"),
                lat = eq(37.5665),
                lng = eq(126.978),
                provider = eq("TMAP"),
                providerPlaceId = eq("home-1"),
            )
        ).thenReturn(expected)

        val response = controller().saveDefaultOrigin(
            principal = principal,
            request = SaveDefaultOriginRequest(
                label = "집",
                placeName = "우리 집",
                address = "서울 중구 세종대로 110",
                lat = 37.5665,
                lng = 126.978,
                provider = "TMAP",
                providerPlaceId = "home-1",
            ),
        )

        assertTrue(response.success)
        assertSame(expected, response.data)
        verify(favoritePlaceService).saveDefaultOrigin(
            memberId = 7L,
            label = "집",
            placeName = "우리 집",
            address = "서울 중구 세종대로 110",
            lat = 37.5665,
            lng = 126.978,
            provider = "TMAP",
            providerPlaceId = "home-1",
        )
    }

    @Test
    fun `clearDefaultOrigin clears only the authenticated member setting`() {
        val response = controller().clearDefaultOrigin(principal)

        assertTrue(response.success)
        verify(favoritePlaceService).clearDefaultOrigin(7L)
    }

    @Test
    fun `default origin endpoints reject a missing principal`() {
        val exception = assertThrows<BusinessException> {
            controller().getDefaultOrigin(null)
        }

        assertEquals(ErrorCode.UNAUTHORIZED, exception.errorCode)
    }

    private fun controller() = FavoritePlaceController(favoritePlaceService)

    private fun defaultOrigin() = FavoritePlaceDto(
        id = 12L,
        label = "집",
        placeName = "우리 집",
        address = "서울 중구 세종대로 110",
        lat = 37.5665,
        lng = 126.978,
        provider = "TMAP",
        providerPlaceId = "home-1",
        defaultOrigin = true,
        sortOrder = 0,
    )
}
