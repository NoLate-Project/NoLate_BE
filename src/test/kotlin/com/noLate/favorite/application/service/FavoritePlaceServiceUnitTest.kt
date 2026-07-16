package com.noLate.favorite.application.service

import com.noLate.favorite.domain.FavoritePlace
import com.noLate.favorite.infrastructure.FavoritePlaceCategoryRepository
import com.noLate.favorite.infrastructure.FavoritePlaceRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class FavoritePlaceServiceUnitTest {

    @Mock
    lateinit var categoryRepository: FavoritePlaceCategoryRepository

    @Mock
    lateinit var placeRepository: FavoritePlaceRepository

    private lateinit var service: FavoritePlaceService

    @BeforeEach
    fun setUp() {
        service = FavoritePlaceService(categoryRepository, placeRepository)
    }

    @Test
    fun `getDefaultOrigin returns the member default place`() {
        val defaultOrigin = FavoritePlace(
            id = 12L,
            memberId = 7L,
            label = "집",
            address = "서울특별시 중구 세종대로 110",
            lat = 37.5665,
            lng = 126.978,
            defaultOrigin = true,
        )
        whenever(placeRepository.findFirstByMemberIdAndDeletedFalseAndDefaultOriginTrueOrderByIdAsc(7L))
            .thenReturn(defaultOrigin)

        val result = service.getDefaultOrigin(7L)

        assertNotNull(result)
        assertEquals(12L, result?.id)
        assertEquals("집", result?.label)
        assertTrue(result?.defaultOrigin == true)
    }

    @Test
    fun `getDefaultOrigin returns null when the member has no default`() {
        whenever(placeRepository.findFirstByMemberIdAndDeletedFalseAndDefaultOriginTrueOrderByIdAsc(7L))
            .thenReturn(null)

        val result = service.getDefaultOrigin(7L)

        assertEquals(null, result)
        verify(categoryRepository, never()).findByIdAndMemberIdAndDeletedFalse(any(), any())
    }

    @Test
    fun `saveDefaultOrigin reuses a matching favorite instead of creating a duplicate`() {
        val existing = FavoritePlace(
            id = 21L,
            memberId = 7L,
            label = "예전 회사",
            placeName = "회사",
            address = "서울 강남구",
            lat = 37.4979,
            lng = 127.0276,
            provider = "TMAP",
            providerPlaceId = "company-1",
            defaultOrigin = false,
            sortOrder = 3,
        )
        whenever(placeRepository.findByMemberIdAndDeletedFalseOrderBySortOrderAscIdAsc(7L))
            .thenReturn(listOf(existing))
        whenever(placeRepository.save(any<FavoritePlace>())).thenAnswer { it.getArgument(0) }

        val result = service.saveDefaultOrigin(
            memberId = 7L,
            label = "회사",
            placeName = "NoLate 오피스",
            address = "서울 강남구 테헤란로",
            lat = 37.4980,
            lng = 127.0277,
            provider = "tmap",
            providerPlaceId = "company-1",
        )

        verify(placeRepository).clearDefaultOrigin(7L)
        verify(placeRepository).save(check {
            assertEquals(21L, it.id)
            assertEquals("회사", it.label)
            assertEquals("NoLate 오피스", it.placeName)
            assertEquals("TMAP", it.provider)
            assertTrue(it.defaultOrigin)
            assertEquals(3, it.sortOrder)
        })
        verify(placeRepository, never()).findMaxSortOrder(7L)
        assertEquals(21L, result.id)
        assertTrue(result.defaultOrigin)
    }

    @Test
    fun `saveDefaultOrigin creates a default favorite when no place matches`() {
        whenever(placeRepository.findByMemberIdAndDeletedFalseOrderBySortOrderAscIdAsc(7L))
            .thenReturn(emptyList())
        whenever(placeRepository.findMaxSortOrder(7L)).thenReturn(4)
        whenever(placeRepository.save(any<FavoritePlace>())).thenAnswer {
            it.getArgument<FavoritePlace>(0).apply { id = 31L }
        }

        val result = service.saveDefaultOrigin(
            memberId = 7L,
            label = null,
            placeName = "서울역",
            address = "서울 중구 한강대로 405",
            lat = 37.5559,
            lng = 126.9723,
            provider = "tmap",
            providerPlaceId = "seoul-station",
        )

        verify(placeRepository).clearDefaultOrigin(7L)
        verify(placeRepository).save(check {
            assertEquals("서울역", it.label)
            assertEquals(5, it.sortOrder)
            assertTrue(it.defaultOrigin)
        })
        assertEquals(31L, result.id)
        assertTrue(result.defaultOrigin)
    }

    @Test
    fun `clearDefaultOrigin clears the member default flag`() {
        service.clearDefaultOrigin(7L)

        verify(placeRepository).clearDefaultOrigin(7L)
    }
}
