package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.ScheduleImportProvider
import com.noLate.schedule.domain.ScheduleImportSource
import com.noLate.schedule.domain.SchedulePlaceDto
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.domain.ScheduleCategoryShare
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.infrastructure.ScheduleShareRepository
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
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageRequest
import java.time.Instant
import java.util.Optional

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
    fun `repeated calendar import returns existing schedule while another occurrence is created`() {
        val memberId = 1L
        val storedBySourceKey = mutableMapOf<String, Schedule>()
        var nextId = 10L
        whenever(
            scheduleRepository.findFirstByMemberIdAndExternalSourceKeyAndDeletedFalse(eq(memberId), any())
        ).thenAnswer { invocation ->
            storedBySourceKey[invocation.getArgument(1)]
        }
        whenever(scheduleRepository.save(any<Schedule>())).thenAnswer { invocation ->
            invocation.getArgument<Schedule>(0).apply {
                id = nextId++
                storedBySourceKey[requireNotNull(externalSourceKey)] = this
            }
        }
        val firstOccurrence = ScheduleImportSource(
            provider = ScheduleImportProvider.APPLE_DEVICE,
            calendarId = "eventkit-calendar",
            eventId = "recurring-event",
            occurrenceStartAt = "2026-06-05T01:00:00Z",
        )

        val first = scheduleService.importSchedule(memberId, scheduleDto(), firstOccurrence)
        val repeated = scheduleService.importSchedule(memberId, scheduleDto(), firstOccurrence)
        val nextOccurrence = scheduleService.importSchedule(
            memberId,
            scheduleDto(
                startAt = "2026-06-12T01:00:00Z",
                endAt = "2026-06-12T02:00:00Z",
            ),
            firstOccurrence.copy(occurrenceStartAt = "2026-06-12T01:00:00Z"),
        )
        val googleSource = ScheduleImportSource(
            provider = ScheduleImportProvider.GOOGLE,
            calendarId = "google:primary",
            eventId = "google-instance-id",
            occurrenceStartAt = "2026-06-05T01:00:00Z",
        )
        val googleFirst = scheduleService.importSchedule(memberId, scheduleDto(), googleSource)
        val googleAfterTimeChange = scheduleService.importSchedule(
            memberId,
            scheduleDto(
                startAt = "2026-06-05T03:00:00Z",
                endAt = "2026-06-05T04:00:00Z",
            ),
            googleSource.copy(occurrenceStartAt = "2026-06-05T03:00:00Z"),
        )

        assertEquals(true, first.created)
        assertEquals(false, repeated.created)
        assertEquals(first.schedule.id, repeated.schedule.id)
        assertEquals(true, nextOccurrence.created)
        assertEquals(true, googleFirst.created)
        assertEquals(false, googleAfterTimeChange.created)
        assertEquals(googleFirst.schedule.id, googleAfterTimeChange.schedule.id)
        assertTrue(storedBySourceKey.keys.all { it.length == 64 })
        assertEquals(3, storedBySourceKey.size)
        verify(scheduleRepository, times(3)).save(any<Schedule>())
    }

    @Test
    fun `legacy calendar import with exact source marker is claimed without creating another schedule`() {
        val memberId = 1L
        val notes = """
            외부 메모

            Apple 캘린더에서 가져온 일정

            원본 캘린더: 개인
        """.trimIndent()
        val existing = scheduleEntity(id = 21L, memberId = memberId).apply {
            this.notes = notes
        }
        val request = scheduleDto().copy(notes = notes)
        val source = ScheduleImportSource(
            provider = ScheduleImportProvider.APPLE_DEVICE,
            calendarId = "calendar-1",
            eventId = "event-1",
            occurrenceStartAt = request.startAt,
        )
        whenever(
            scheduleRepository.findAllByMemberIdAndTitleAndStartAtAndEndAtAndDeletedFalseOrderByIdAsc(
                memberId,
                existing.title,
                existing.startAt,
                existing.endAt,
            )
        ).thenReturn(listOf(existing))
        whenever(scheduleRepository.save(existing)).thenReturn(existing)

        val result = scheduleService.importSchedule(memberId, request, source)

        assertEquals(false, result.created)
        assertEquals(existing.id, result.schedule.id)
        assertEquals(64, existing.externalSourceKey?.length)
        verify(scheduleRepository, times(1)).save(existing)
    }

    @Test
    fun `viewer cannot forge a shared category snapshot when creating a schedule`() {
        val categoryRepository = org.mockito.kotlin.mock<ScheduleCategoryRepository>()
        val shareRepository = org.mockito.kotlin.mock<ScheduleCategoryShareRepository>()
        val securedService = ScheduleService(
            scheduleRepository = scheduleRepository,
            objectMapper = objectMapper,
            subscriptionPolicyService = subscriptionPolicyService,
            categoryRepository = categoryRepository,
            categoryShareRepository = shareRepository,
        )
        whenever(categoryRepository.findById(1L)).thenReturn(
            Optional.of(ScheduleCategory(id = 1L, memberId = 99L, title = "공유", color = "#000000"))
        )
        whenever(shareRepository.findByCategoryIdAndTargetMemberId(1L, 1L)).thenReturn(
            ScheduleCategoryShare(
                categoryId = 1L,
                ownerMemberId = 99L,
                targetMemberId = 1L,
                permission = ScheduleSharePermission.VIEWER,
            )
        )

        val exception = assertThrows<BusinessException> {
            securedService.addSchedule(1L, scheduleDto())
        }

        assertEquals(com.noLate.global.error.ErrorCode.FORBIDDEN, exception.errorCode)
        verify(scheduleRepository, never()).save(any<Schedule>())
    }

    @Test
    fun `updateSchedule updates only owned active schedule`() {
        // given
        val memberId = 1L
        val scheduleId = 10L
        val existing = scheduleEntity(id = scheduleId, memberId = memberId, title = "Old schedule")
        val updateDto = scheduleDto(title = "Updated schedule", travelMinutes = 40)

        whenever(scheduleRepository.findOwnedScheduleDetail(scheduleId, memberId))
            .thenReturn(existing)
        whenever(scheduleRepository.save(existing)).thenReturn(existing)

        // when
        val result = scheduleService.updateSchedule(memberId, scheduleId, updateDto)

        // then
        verify(scheduleRepository, times(1)).findOwnedScheduleDetail(scheduleId, memberId)
        verify(scheduleRepository, times(1)).save(existing)
        assertEquals("Updated schedule", result.title)
        assertEquals(40, result.travelMinutes)
    }

    @Test
    fun `adding a configured route clears quick share setup marker`() {
        val memberId = 1L
        val scheduleId = 10L
        val existing = scheduleEntity(id = scheduleId, memberId = memberId).apply {
            routeSetupRequired = true
        }
        val updateDto = scheduleDto().copy(routeSetupRequired = null)

        whenever(scheduleRepository.findOwnedScheduleDetail(scheduleId, memberId))
            .thenReturn(existing)
        whenever(scheduleRepository.save(existing)).thenReturn(existing)

        val result = scheduleService.updateSchedule(memberId, scheduleId, updateDto)

        assertEquals(false, existing.routeSetupRequired)
        assertEquals(false, result.routeSetupRequired)
    }

    @Test
    fun `deleteSchedule changes deleted flag instead of hard deleting`() {
        // given
        val memberId = 1L
        val scheduleId = 10L
        val existing = scheduleEntity(id = scheduleId, memberId = memberId).apply {
            externalSourceKey = "calendar-source-key"
        }

        whenever(scheduleRepository.findOwnedScheduleDetail(scheduleId, memberId))
            .thenReturn(existing)
        whenever(scheduleRepository.save(existing)).thenReturn(existing)

        // when
        scheduleService.deleteSchedule(memberId, scheduleId)

        // then
        verify(scheduleRepository, times(1)).save(check {
            assertEquals(true, it.deleted)
            assertNotNull(it.deletedAt)
            assertEquals(null, it.externalSourceKey)
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

        whenever(scheduleRepository.findOwnedScheduleDetail(scheduleId, memberId))
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

        whenever(scheduleRepository.findOwnedScheduleDetail(scheduleId, memberId))
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
    fun `getScheduleList returns the effective direct or category share permission`() {
        val memberId = 1L
        val directShareRepository = mock<ScheduleShareRepository>()
        val categoryShareRepository = mock<ScheduleCategoryShareRepository>()
        val permissionAwareService = ScheduleService(
            scheduleRepository = scheduleRepository,
            objectMapper = objectMapper,
            subscriptionPolicyService = subscriptionPolicyService,
            categoryShareRepository = categoryShareRepository,
            scheduleShareRepository = directShareRepository,
        )
        val directViewer = scheduleEntity(id = 2L, memberId = 99L, categoryId = "10")
        val categoryEditor = scheduleEntity(id = 3L, memberId = 99L, categoryId = "20")
        val both = scheduleEntity(id = 4L, memberId = 99L, categoryId = "20")
        whenever(scheduleRepository.findScheduleList(memberId))
            .thenReturn(listOf(directViewer, categoryEditor, both))
        whenever(
            directShareRepository.findAllByTargetMemberIdAndStatusAndDeletedFalseOrderByIdDesc(
                memberId,
                ScheduleShareStatus.ACTIVE,
            )
        ).thenReturn(
            listOf(
                ScheduleShare(
                    id = 12L,
                    scheduleId = 2L,
                    ownerMemberId = 99L,
                    targetMemberId = memberId,
                    permission = ScheduleSharePermission.VIEWER,
                    status = ScheduleShareStatus.ACTIVE,
                ),
                ScheduleShare(
                    id = 14L,
                    scheduleId = 4L,
                    ownerMemberId = 99L,
                    targetMemberId = memberId,
                    permission = ScheduleSharePermission.VIEWER,
                    status = ScheduleShareStatus.ACTIVE,
                ),
            )
        )
        whenever(
            categoryShareRepository.findAllByTargetMemberIdAndStatusAndDeletedFalseOrderByIdDesc(
                memberId,
                ScheduleShareStatus.ACTIVE,
            )
        ).thenReturn(
            listOf(
                ScheduleCategoryShare(
                    id = 20L,
                    categoryId = 20L,
                    ownerMemberId = 99L,
                    targetMemberId = memberId,
                    permission = ScheduleSharePermission.EDITOR,
                    status = ScheduleShareStatus.ACTIVE,
                )
            )
        )

        val result = permissionAwareService.getScheduleList(memberId).associateBy { it.id }

        assertEquals(ScheduleSharePermission.VIEWER, result.getValue(2L).sharePermission)
        assertEquals(false, result.getValue(2L).category.shared)
        assertEquals(null, result.getValue(2L).category.sharePermission)
        assertEquals(ScheduleSharePermission.EDITOR, result.getValue(3L).sharePermission)
        assertEquals(true, result.getValue(3L).category.shared)
        assertEquals(ScheduleSharePermission.EDITOR, result.getValue(3L).category.sharePermission)
        // Direct VIEWER plus category EDITOR must expose the strongest effective permission.
        assertEquals(ScheduleSharePermission.EDITOR, result.getValue(4L).sharePermission)
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

        whenever(scheduleRepository.findOwnedScheduleDetail(scheduleId, memberId))
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
        categoryId: String = "1",
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
                categoryId = categoryId,
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
