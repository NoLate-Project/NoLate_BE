package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.SchedulePlaceDto
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.subscription.application.SubscriptionPolicyService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageRequest
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class ScheduleServiceUnitTest {

    @Mock
    lateinit var scheduleRepository: ScheduleRepository

    @Mock
    lateinit var subscriptionPolicyService: SubscriptionPolicyService

    private lateinit var scheduleService: ScheduleService

    private val objectMapper = ObjectMapper()

    @BeforeEach
    fun setUp() {
        scheduleService = ScheduleService(
            scheduleRepository = scheduleRepository,
            objectMapper = objectMapper,
            subscriptionPolicyService = subscriptionPolicyService,
        )
    }

    @Test
    fun `addSchedule stores schedule body and route json`() {
        // given
        val memberId = 1L
        val route = objectMapper.readTree("""{"id":"route-1","minutes":25}""")
        val dto = scheduleDto(route = route)

        whenever(scheduleRepository.save(any<Schedule>()))
            .thenAnswer { invocation ->
                invocation.getArgument<Schedule>(0).apply { id = 10L }
            }

        // when
        val result = scheduleService.addSchedule(memberId, dto)

        // then
        verify(scheduleRepository, times(1)).save(check {
            assertEquals(memberId, it.memberId)
            assertEquals("Team sync", it.title)
            assertEquals(Instant.parse("2026-06-05T01:00:00Z"), it.startAt)
            assertEquals(Instant.parse("2026-06-05T02:00:00Z"), it.endAt)
            assertEquals(25, it.route?.travelMinutes)
            assertEquals(ScheduleTravelMode.TRANSIT, it.route?.travelMode)
            assertEquals("Office", it.route?.locationName)
            assertEquals("Work", it.categorySnapshot?.title)
            assertEquals("Home", it.route?.originName)
            assertEquals("Office", it.route?.destinationName)
            assertTrue(it.route?.routeJson?.contains("route-1") == true)
        })
        assertEquals(10L, result.id)
        assertEquals("Team sync", result.title)
        assertNotNull(result.route)
    }

    @Test
    fun `updateSchedule updates only owned active schedule`() {
        // given
        val memberId = 1L
        val scheduleId = 10L
        val existing = scheduleEntity(id = scheduleId, memberId = memberId, title = "Old schedule")
        val updateDto = scheduleDto(title = "Updated schedule", travelMinutes = 40)

        whenever(scheduleRepository.findScheduleDetail(scheduleId, memberId))
            .thenReturn(existing)
        whenever(scheduleRepository.save(existing)).thenReturn(existing)

        // when
        val result = scheduleService.updateSchedule(memberId, scheduleId, updateDto)

        // then
        verify(scheduleRepository, times(1)).findScheduleDetail(scheduleId, memberId)
        verify(scheduleRepository, times(1)).save(existing)
        assertEquals("Updated schedule", result.title)
        assertEquals(40, result.travelMinutes)
    }

    @Test
    fun `deleteSchedule changes deleted flag instead of hard deleting`() {
        // given
        val memberId = 1L
        val scheduleId = 10L
        val existing = scheduleEntity(id = scheduleId, memberId = memberId)

        whenever(scheduleRepository.findScheduleDetail(scheduleId, memberId))
            .thenReturn(existing)
        whenever(scheduleRepository.save(existing)).thenReturn(existing)

        // when
        scheduleService.deleteSchedule(memberId, scheduleId)

        // then
        verify(scheduleRepository, times(1)).save(check {
            assertEquals(true, it.deleted)
            assertNotNull(it.deletedAt)
        })
    }

    @Test
    fun `markDeparted disables route notification without removing route data`() {
        val memberId = 1L
        val scheduleId = 10L
        val existing = scheduleEntity(
            id = scheduleId,
            memberId = memberId,
            notificationEnabled = true,
        )

        whenever(scheduleRepository.findScheduleDetail(scheduleId, memberId))
            .thenReturn(existing)
        whenever(scheduleRepository.save(existing)).thenReturn(existing)

        val result = scheduleService.markDeparted(memberId, scheduleId)

        verify(scheduleRepository).save(check {
            assertEquals(false, it.route?.notificationEnabled)
            assertEquals(null, it.route?.notificationLeadMinutes)
            assertEquals(null, it.route?.notificationIntervalMinutes)
            assertNotNull(it.route?.departedAt)
            assertEquals(25, it.route?.travelMinutes)
        })
        assertEquals(false, result.notificationEnabled)
        assertNotNull(result.departedAt)
        assertEquals(25, result.travelMinutes)
    }

    @Test
    fun `markDeparted keeps first departedAt when notification action is repeated`() {
        val memberId = 1L
        val scheduleId = 10L
        val firstDepartedAt = Instant.parse("2026-06-05T00:40:00Z")
        val existing = scheduleEntity(
            id = scheduleId,
            memberId = memberId,
            notificationEnabled = true,
            departedAt = firstDepartedAt,
        )

        whenever(scheduleRepository.findScheduleDetail(scheduleId, memberId))
            .thenReturn(existing)
        whenever(scheduleRepository.save(existing)).thenReturn(existing)

        val result = scheduleService.markDeparted(memberId, scheduleId)

        verify(scheduleRepository).save(check {
            assertEquals(firstDepartedAt, it.route?.departedAt)
            assertEquals(false, it.route?.notificationEnabled)
        })
        assertEquals(firstDepartedAt.toString(), result.departedAt)
    }

    @Test
    fun `getCalendarScheduleList reads schedules overlapping requested range`() {
        // given
        val memberId = 1L
        val rangeStart = "2026-06-01T00:00:00Z"
        val rangeEnd = "2026-06-30T23:59:59Z"

        whenever(
            scheduleRepository.findOverlappingScheduleList(
                memberId = memberId,
                rangeEnd = Instant.parse(rangeEnd),
                rangeStart = Instant.parse(rangeStart),
            )
        ).thenReturn(listOf(scheduleEntity(id = 1L, memberId = memberId)))

        // when
        val result = scheduleService.getCalendarScheduleList(memberId, rangeStart, rangeEnd)

        // then
        assertEquals(1, result.size)
        verify(scheduleRepository, times(1))
            .findOverlappingScheduleList(
                memberId = memberId,
                rangeEnd = Instant.parse(rangeEnd),
                rangeStart = Instant.parse(rangeStart),
            )
    }

    @Test
    fun `getDailyScheduleList converts local date to Seoul day range`() {
        // given
        val memberId = 1L
        val dayStart = Instant.parse("2026-06-04T15:00:00Z")
        val dayEnd = Instant.parse("2026-06-05T14:59:59.999999999Z")

        whenever(
            scheduleRepository.findOverlappingScheduleList(
                memberId = memberId,
                rangeEnd = dayEnd,
                rangeStart = dayStart,
            )
        ).thenReturn(listOf(scheduleEntity(id = 1L, memberId = memberId)))

        // when
        val result = scheduleService.getDailyScheduleList(memberId, "2026-06-05")

        // then
        assertEquals(1, result.size)
        verify(scheduleRepository, times(1))
            .findOverlappingScheduleList(
                memberId = memberId,
                rangeEnd = dayEnd,
                rangeStart = dayStart,
            )
    }

    @Test
    fun `getUpcomingScheduleList reads limited schedules after base time`() {
        // given
        val memberId = 1L
        val fromAt = "2026-06-05T00:00:00Z"

        whenever(
            scheduleRepository.findUpcomingScheduleList(
                memberId = memberId,
                fromAt = Instant.parse(fromAt),
                pageable = PageRequest.of(0, 3),
            )
        ).thenReturn(listOf(scheduleEntity(id = 1L, memberId = memberId)))

        // when
        val result = scheduleService.getUpcomingScheduleList(memberId, fromAt, 3)

        // then
        assertEquals(1, result.size)
        verify(scheduleRepository, times(1))
            .findUpcomingScheduleList(
                memberId = memberId,
                fromAt = Instant.parse(fromAt),
                pageable = PageRequest.of(0, 3),
            )
    }

    @Test
    fun `searchScheduleList normalizes blank filters before repository call`() {
        // given
        val memberId = 1L
        val startAt = "2026-06-01T00:00:00Z"
        val endAt = "2026-06-30T23:59:59Z"

        whenever(
            scheduleRepository.searchScheduleList(
                memberId = memberId,
                keyword = null,
                categoryId = null,
                rangeStart = Instant.parse(startAt),
                rangeEnd = Instant.parse(endAt),
            )
        ).thenReturn(emptyList())

        // when
        val result = scheduleService.searchScheduleList(
            memberId = memberId,
            keyword = "   ",
            categoryId = "",
            startAt = startAt,
            endAt = endAt,
        )

        // then
        assertEquals(0, result.size)
        verify(scheduleRepository, times(1)).searchScheduleList(
            memberId = memberId,
            keyword = null,
            categoryId = null,
            rangeStart = Instant.parse(startAt),
            rangeEnd = Instant.parse(endAt),
        )
    }

    @Test
    fun `getDepartureReadyScheduleList reads schedules with travel data`() {
        // given
        val memberId = 1L
        val fromAt = "2026-06-05T00:00:00Z"
        val toAt = "2026-06-06T00:00:00Z"

        whenever(
            scheduleRepository.findDepartureReadyScheduleList(
                memberId = memberId,
                fromAt = Instant.parse(fromAt),
                toAt = Instant.parse(toAt),
            )
        ).thenReturn(listOf(scheduleEntity(id = 1L, memberId = memberId, travelMinutes = 20)))

        // when
        val result = scheduleService.getDepartureReadyScheduleList(memberId, fromAt, toAt)

        // then
        assertEquals(1, result.size)
        verify(scheduleRepository, times(1)).findDepartureReadyScheduleList(
            memberId = memberId,
            fromAt = Instant.parse(fromAt),
            toAt = Instant.parse(toAt),
        )
    }

    @Test
    fun `addSchedule throws when endAt is before startAt`() {
        // given
        val invalidDto = scheduleDto(
            startAt = "2026-06-05T02:00:00Z",
            endAt = "2026-06-05T01:00:00Z",
        )

        // when & then
        assertThrows<BusinessException> {
            scheduleService.addSchedule(1L, invalidDto)
        }
        verify(scheduleRepository, never()).save(any<Schedule>())
    }

    @Test
    fun `addSchedule stores a point-in-time schedule when end time is omitted`() {
        val dto = scheduleDto().copy(
            endAt = null,
            hasEndTime = false,
        )
        whenever(scheduleRepository.save(any<Schedule>()))
            .thenAnswer { invocation ->
                invocation.getArgument<Schedule>(0).apply { id = 20L }
            }

        val result = scheduleService.addSchedule(1L, dto)

        verify(scheduleRepository).save(check {
            assertEquals(it.startAt, it.endAt)
            assertEquals(false, it.hasEndTime)
        })
        assertEquals(false, result.hasEndTime)
        assertEquals(result.startAt, result.endAt)
    }

    @Test
    fun `updateSchedule throws when target schedule does not exist`() {
        // given
        val memberId = 1L
        val scheduleId = 404L

        whenever(scheduleRepository.findScheduleDetail(scheduleId, memberId))
            .thenReturn(null)

        // when & then
        assertThrows<BusinessException> {
            scheduleService.updateSchedule(memberId, scheduleId, scheduleDto())
        }
        verify(scheduleRepository, never()).save(any<Schedule>())
    }

    private fun scheduleDto(
        title: String = "Team sync",
        startAt: String = "2026-06-05T01:00:00Z",
        endAt: String = "2026-06-05T02:00:00Z",
        travelMinutes: Int? = 25,
        route: JsonNode? = null,
    ): ScheduleDto =
        ScheduleDto(
            title = title,
            startAt = startAt,
            endAt = endAt,
            allDay = false,
            travelMinutes = travelMinutes,
            departAt = "2026-06-05T00:30:00Z",
            travelMode = ScheduleTravelMode.TRANSIT,
            origin = SchedulePlaceDto(name = "Home", address = "1 Home St", lat = 37.1, lng = 127.1),
            destination = SchedulePlaceDto(name = "Office", address = "1 Office St", lat = 37.2, lng = 127.2),
            locationName = "Office",
            category = ScheduleCategoryDto(id = "1", title = "Work", color = "#f44336"),
            notes = "Memo",
            route = route,
        )

    private fun scheduleEntity(
        id: Long = 1L,
        memberId: Long = 1L,
        title: String = "Team sync",
        travelMinutes: Int? = 25,
        notificationEnabled: Boolean = false,
        departedAt: Instant? = null,
    ): Schedule =
        Schedule(
            id = id,
            memberId = memberId,
            title = title,
            startAt = Instant.parse("2026-06-05T01:00:00Z"),
            endAt = Instant.parse("2026-06-05T02:00:00Z"),
            allDay = false,
            notes = "Memo",
        ).apply {
            updateCategorySnapshot(
                categoryId = "1",
                title = "Work",
                color = "#f44336",
            )
            updateRoute(
                travelMinutes = travelMinutes,
                departAt = Instant.parse("2026-06-05T00:30:00Z"),
                departedAt = departedAt,
                travelMode = ScheduleTravelMode.TRANSIT,
                locationName = "Office",
                originName = "Home",
                originAddress = "1 Home St",
                originLat = 37.1,
                originLng = 127.1,
                destinationName = "Office",
                destinationAddress = "1 Office St",
                destinationLat = 37.2,
                destinationLng = 127.2,
                routeJson = """{"id":"route-1"}""",
                notificationEnabled = notificationEnabled,
                notificationLeadMinutes = 60.takeIf { notificationEnabled },
                notificationIntervalMinutes = 20.takeIf { notificationEnabled },
            )
        }

}
