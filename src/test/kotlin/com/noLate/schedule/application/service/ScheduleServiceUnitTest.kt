package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleAlertMode
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
import com.noLate.schedule.domain.ScheduleCalendar
import com.noLate.schedule.domain.ScheduleCalendarMember
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.ScheduleShareContentMode
import com.noLate.schedule.application.cache.ScheduleCalendarCacheService
import com.noLate.schedule.application.cache.ScheduleCalendarCacheScope
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarRepository
import com.noLate.subscription.application.SubscriptionPolicyService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.env.MockEnvironment
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import java.time.Duration
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
    private val calendarTransactionManager = ImmediateTransactionManager()

    @BeforeEach
    fun setUp() {
        scheduleService = ScheduleService(
            scheduleRepository = scheduleRepository,
            objectMapper = objectMapper,
            subscriptionPolicyService = subscriptionPolicyService,
            sharingAvailabilityPolicy = ScheduleSharingAvailabilityPolicy(
                MockEnvironment().withProperty("schedule.sharing.enabled", "true"),
            ),
            transactionManager = calendarTransactionManager,
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
    fun `legacy calendar import is claimed when the new request omits old system memo lines`() {
        val memberId = 1L
        val existing = scheduleEntity(id = 22L, memberId = memberId).apply {
            notes = """
                외부 메모

                Apple 캘린더에서 가져온 일정

                원본 캘린더: 개인
            """.trimIndent()
        }
        val request = scheduleDto().copy(notes = "외부 메모")
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
            sharingAvailabilityPolicy = ScheduleSharingAvailabilityPolicy(
                MockEnvironment().withProperty("schedule.sharing.enabled", "true"),
            ),
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
    fun `legacy update without alert mode preserves an existing alarm preference`() {
        val memberId = 1L
        val scheduleId = 10L
        val existing = scheduleEntity(id = scheduleId, memberId = memberId).apply {
            route?.alertMode = ScheduleAlertMode.ALARM
        }
        val legacyUpdate = scheduleDto(title = "Updated by older client")

        whenever(scheduleRepository.findOwnedScheduleDetail(scheduleId, memberId))
            .thenReturn(existing)
        whenever(scheduleRepository.save(existing)).thenReturn(existing)

        val result = scheduleService.updateSchedule(memberId, scheduleId, legacyUpdate)

        assertEquals(ScheduleAlertMode.ALARM, existing.route?.alertMode)
        assertEquals(ScheduleAlertMode.ALARM, result.alertMode)
    }

    @Test
    fun `stale schedule update preserves confirmed coordinates for the same destination`() {
        val memberId = 1L
        val scheduleId = 10L
        val existing = scheduleEntity(id = scheduleId, memberId = memberId).apply {
            route?.destinationName = "강남역"
            route?.destinationAddress = "서울 강남구 강남대로 지하 396"
            route?.destinationLat = 37.49812971
            route?.destinationLng = 127.02868505
        }
        val staleUpdate = scheduleDto(title = "좌표 보강 전에 열어 둔 일정").copy(
            destination = SchedulePlaceDto(
                name = "강남역[2호선] 2번 출구",
                address = "서울 강남구 강남대로 지하 396",
                lat = null,
                lng = null,
            ),
        )

        whenever(scheduleRepository.findOwnedScheduleDetail(scheduleId, memberId)).thenReturn(existing)
        whenever(scheduleRepository.save(existing)).thenReturn(existing)

        val result = scheduleService.updateSchedule(memberId, scheduleId, staleUpdate)

        assertEquals(37.49812971, existing.route?.destinationLat)
        assertEquals(127.02868505, existing.route?.destinationLng)
        assertEquals(37.49812971, result.destination?.lat)
        assertEquals(127.02868505, result.destination?.lng)
    }

    @Test
    fun `schedule update does not inherit coordinates when destination changed`() {
        val memberId = 1L
        val scheduleId = 10L
        val existing = scheduleEntity(id = scheduleId, memberId = memberId).apply {
            route?.destinationName = "강남역"
            route?.destinationAddress = "서울 강남구 강남대로 지하 396"
            route?.destinationLat = 37.49812971
            route?.destinationLng = 127.02868505
        }
        val changedDestination = scheduleDto().copy(
            destination = SchedulePlaceDto(name = "역삼역", lat = null, lng = null),
        )

        whenever(scheduleRepository.findOwnedScheduleDetail(scheduleId, memberId)).thenReturn(existing)
        whenever(scheduleRepository.save(existing)).thenReturn(existing)

        scheduleService.updateSchedule(memberId, scheduleId, changedDestination)

        assertEquals("역삼역", existing.route?.destinationName)
        assertNull(existing.route?.destinationLat)
        assertNull(existing.route?.destinationLng)
    }

    @Test
    fun `stale schedule update does not preserve coordinates for the same name at another address`() {
        val memberId = 1L
        val scheduleId = 10L
        val existing = scheduleEntity(id = scheduleId, memberId = memberId).apply {
            route?.destinationName = "우리집"
            route?.destinationAddress = "서울특별시 마포구 월드컵북로 1"
            route?.destinationLat = 37.566
            route?.destinationLng = 126.901
        }
        val anotherHome = scheduleDto().copy(
            destination = SchedulePlaceDto(
                name = "우리집",
                address = "부산광역시 해운대구 해운대해변로 1",
                lat = null,
                lng = null,
            ),
        )

        whenever(scheduleRepository.findOwnedScheduleDetail(scheduleId, memberId)).thenReturn(existing)
        whenever(scheduleRepository.save(existing)).thenReturn(existing)

        scheduleService.updateSchedule(memberId, scheduleId, anotherHome)

        assertEquals("우리집", existing.route?.destinationName)
        assertEquals("부산광역시 해운대구 해운대해변로 1", existing.route?.destinationAddress)
        assertNull(existing.route?.destinationLat)
        assertNull(existing.route?.destinationLng)
    }

    @Test
    fun `add schedule rejects a partial destination coordinate pair`() {
        val invalid = scheduleDto().copy(
            destination = SchedulePlaceDto(name = "강남역", lat = 37.4979, lng = null),
            notificationEnabled = false,
        )

        val error = assertThrows<BusinessException> {
            scheduleService.addSchedule(1L, invalid)
        }

        assertEquals(com.noLate.global.error.ErrorCode.INVALID_INPUT, error.errorCode)
        verify(scheduleRepository, never()).save(any<Schedule>())
    }

    @Test
    fun `update schedule rejects non finite coordinates without mutating the saved route`() {
        val memberId = 1L
        val scheduleId = 10L
        val existing = scheduleEntity(id = scheduleId, memberId = memberId)
        val invalid = scheduleDto().copy(
            destination = SchedulePlaceDto(
                name = "강남역",
                lat = Double.NaN,
                lng = 127.0276,
            ),
            notificationEnabled = false,
        )
        whenever(scheduleRepository.findOwnedScheduleDetail(scheduleId, memberId)).thenReturn(existing)

        val error = assertThrows<BusinessException> {
            scheduleService.updateSchedule(memberId, scheduleId, invalid)
        }

        assertEquals(com.noLate.global.error.ErrorCode.INVALID_INPUT, error.errorCode)
        assertEquals(37.2, existing.route?.destinationLat)
        assertEquals(127.2, existing.route?.destinationLng)
        verify(scheduleRepository, never()).save(existing)
    }

    @Test
    fun `update schedule rejects out of range origin coordinates`() {
        val memberId = 1L
        val scheduleId = 10L
        val existing = scheduleEntity(id = scheduleId, memberId = memberId)
        val invalid = scheduleDto().copy(
            origin = SchedulePlaceDto(name = "출발지", lat = 91.0, lng = 127.0),
            notificationEnabled = false,
        )
        whenever(scheduleRepository.findOwnedScheduleDetail(scheduleId, memberId)).thenReturn(existing)

        val error = assertThrows<BusinessException> {
            scheduleService.updateSchedule(memberId, scheduleId, invalid)
        }

        assertEquals(com.noLate.global.error.ErrorCode.INVALID_INPUT, error.errorCode)
        verify(scheduleRepository, never()).save(existing)
    }

    @Test
    fun `shared editor can update while keeping owner's existing category snapshot`() {
        val editorId = 2L
        val scheduleId = 10L
        val existing = scheduleEntity(id = scheduleId, memberId = 99L, title = "Old schedule").apply {
            route?.alertMode = ScheduleAlertMode.ALARM
        }
        val accessPolicy = mock<ScheduleAccessPolicy>()
        val categoryRepository = mock<ScheduleCategoryRepository>()
        val categoryShareRepository = mock<ScheduleCategoryShareRepository>()
        val securedService = ScheduleService(
            scheduleRepository = scheduleRepository,
            objectMapper = objectMapper,
            subscriptionPolicyService = subscriptionPolicyService,
            scheduleAccessPolicy = accessPolicy,
            categoryRepository = categoryRepository,
            categoryShareRepository = categoryShareRepository,
            sharingAvailabilityPolicy = ScheduleSharingAvailabilityPolicy(
                MockEnvironment().withProperty("schedule.sharing.enabled", "true"),
            ),
        )
        whenever(scheduleRepository.findActiveForTravelPlanUpdate(scheduleId)).thenReturn(existing)
        val editorAccess = ScheduleAccessDecision(
            canView = true,
            canEdit = true,
            travelEnabled = false,
            canViewAllTravelPlans = true,
            effectivePermission = ScheduleSharePermission.EDITOR,
        )
        whenever(accessPolicy.resolve(editorId, existing)).thenReturn(editorAccess)
        whenever(scheduleRepository.save(existing)).thenReturn(existing)

        val result = securedService.updateSchedule(
            editorId,
            scheduleId,
            scheduleDto(title = "Editor update").copy(
                alertMode = ScheduleAlertMode.STANDARD,
            ),
        )

        assertEquals("Editor update", result.title)
        assertEquals("Work", result.category.title)
        assertEquals(ScheduleAlertMode.ALARM, result.alertMode)
        verify(categoryRepository, never()).findById(any())
        verify(categoryShareRepository, never()).findByCategoryIdAndTargetMemberId(any(), any())
    }

    @Test
    fun `moving schedule locks source and target calendars in ascending id order`() {
        val memberId = 1L
        val scheduleId = 10L
        val sourceCalendarId = 9L
        val targetCalendarId = 3L
        val calendarRepository = mock<ScheduleCalendarRepository>()
        val calendarMemberRepository = mock<ScheduleCalendarMemberRepository>()
        val accessPolicy = mock<ScheduleAccessPolicy>()
        val securedService = ScheduleService(
            scheduleRepository = scheduleRepository,
            objectMapper = objectMapper,
            subscriptionPolicyService = subscriptionPolicyService,
            scheduleAccessPolicy = accessPolicy,
            calendarRepository = calendarRepository,
            calendarMemberRepository = calendarMemberRepository,
            sharingAvailabilityPolicy = ScheduleSharingAvailabilityPolicy(
                MockEnvironment().withProperty("schedule.sharing.enabled", "true"),
            ),
        )
        val existing = scheduleEntity(id = scheduleId, memberId = 99L).apply {
            calendarId = sourceCalendarId
        }
        val targetCalendar = ScheduleCalendar(
            id = targetCalendarId,
            ownerMemberId = 99L,
            title = "대상",
            defaultContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
        )
        val sourceCalendar = ScheduleCalendar(
            id = sourceCalendarId,
            ownerMemberId = 99L,
            title = "원본",
        )
        val editor = ScheduleCalendarMember(
            id = 30L,
            calendarId = targetCalendarId,
            memberId = memberId,
            role = ScheduleCalendarRole.EDITOR,
        )
        whenever(scheduleRepository.findActiveForTravelPlanUpdate(scheduleId)).thenReturn(existing)
        val editorAccess = ScheduleAccessDecision(
            canView = true,
            canEdit = true,
            travelEnabled = true,
            canViewAllTravelPlans = true,
        )
        whenever(accessPolicy.resolve(memberId, existing)).thenReturn(editorAccess)
        whenever(calendarRepository.findAllForUpdate(listOf(targetCalendarId, sourceCalendarId)))
            .thenReturn(listOf(targetCalendar, sourceCalendar))
        whenever(
            calendarMemberRepository.findByCalendarIdAndMemberIdAndStatusAndDeletedFalse(
                targetCalendarId,
                memberId,
                com.noLate.schedule.domain.ScheduleCalendarMemberStatus.ACTIVE,
            )
        ).thenReturn(editor)
        whenever(scheduleRepository.save(existing)).thenReturn(existing)

        securedService.updateSchedule(
            memberId,
            scheduleId,
            scheduleDto().copy(calendarId = targetCalendarId),
        )

        verify(calendarRepository).findAllForUpdate(listOf(targetCalendarId, sourceCalendarId))
        assertEquals(targetCalendarId, existing.calendarId)
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
    fun `travel mode alone does not clear quick share setup marker`() {
        val modeOnlyDto = scheduleDto().copy(
            travelMinutes = null,
            departAt = null,
            travelMode = ScheduleTravelMode.CAR,
            origin = null,
            destination = null,
            locationName = null,
            routeSetupRequired = true,
            route = null,
        )
        whenever(scheduleRepository.save(any<Schedule>()))
            .thenAnswer { invocation ->
                invocation.getArgument<Schedule>(0).apply { id = 10L }
            }

        val result = scheduleService.addSchedule(1L, modeOnlyDto)

        verify(scheduleRepository).save(check {
            assertEquals(true, it.routeSetupRequired)
        })
        assertEquals(true, result.routeSetupRequired)
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
    fun `getCalendarScheduleList allows an exact 190 day range`() {
        val memberId = 1L
        val rangeStart = Instant.parse("2026-01-01T00:00:00Z")
        val rangeEnd = rangeStart.plus(Duration.ofDays(190))
        whenever(
            scheduleRepository.findOverlappingScheduleList(memberId, rangeStart, rangeEnd)
        ).thenReturn(emptyList())

        val result = scheduleService.getCalendarScheduleList(
            memberId,
            rangeStart.toString(),
            rangeEnd.toString(),
        )

        assertTrue(result.isEmpty())
        verify(scheduleRepository).findOverlappingScheduleList(memberId, rangeStart, rangeEnd)
    }

    @Test
    fun `getCalendarScheduleList rejects a range over 190 days before repository access`() {
        val memberId = 1L
        val rangeStart = Instant.parse("2026-01-01T00:00:00Z")
        val rangeEnd = rangeStart.plus(Duration.ofDays(190)).plusNanos(1)

        val error = assertThrows<BusinessException> {
            scheduleService.getCalendarScheduleList(
                memberId,
                rangeStart.toString(),
                rangeEnd.toString(),
            )
        }

        assertEquals(com.noLate.global.error.ErrorCode.INVALID_INPUT, error.errorCode)
        verify(scheduleRepository, never()).findOverlappingScheduleList(any(), any(), any())
        verify(scheduleRepository, never()).findOwnedOverlappingScheduleList(any(), any(), any())
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
            sharingAvailabilityPolicy = ScheduleSharingAvailabilityPolicy(
                MockEnvironment().withProperty("schedule.sharing.enabled", "true"),
            ),
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
    fun `central access policy preserves category share metadata in schedule responses`() {
        val memberId = 1L
        val scheduleId = 3L
        val accessPolicy = mock<ScheduleAccessPolicy>()
        val categorySharedSchedule = scheduleEntity(
            id = scheduleId,
            memberId = 99L,
            categoryId = "20",
        )
        val securedService = ScheduleService(
            scheduleRepository = scheduleRepository,
            objectMapper = objectMapper,
            subscriptionPolicyService = subscriptionPolicyService,
            scheduleAccessPolicy = accessPolicy,
            sharingAvailabilityPolicy = ScheduleSharingAvailabilityPolicy(
                MockEnvironment().withProperty("schedule.sharing.enabled", "true"),
            ),
        )
        whenever(scheduleRepository.findScheduleList(memberId))
            .thenReturn(listOf(categorySharedSchedule))
        whenever(accessPolicy.resolveAll(memberId, listOf(categorySharedSchedule)))
            .thenReturn(
                mapOf(
                    scheduleId to ScheduleAccessDecision(
                        canView = true,
                        canEdit = true,
                        travelEnabled = false,
                        canViewAllTravelPlans = true,
                        effectivePermission = ScheduleSharePermission.EDITOR,
                        categoryPermission = ScheduleSharePermission.EDITOR,
                    )
                )
            )

        val result = securedService.getScheduleList(memberId).single()

        assertEquals(ScheduleSharePermission.EDITOR, result.sharePermission)
        assertEquals(true, result.category.shared)
        assertEquals(ScheduleSharePermission.EDITOR, result.category.sharePermission)
    }

    @Test
    fun `all-owned schedule results skip bulk share access resolution`() {
        val memberId = 1L
        val accessPolicy = mock<ScheduleAccessPolicy>()
        val first = scheduleEntity(id = 1L, memberId = memberId)
        val second = scheduleEntity(id = 2L, memberId = memberId)
        val securedService = ScheduleService(
            scheduleRepository = scheduleRepository,
            objectMapper = objectMapper,
            subscriptionPolicyService = subscriptionPolicyService,
            scheduleAccessPolicy = accessPolicy,
            sharingAvailabilityPolicy = ScheduleSharingAvailabilityPolicy(
                MockEnvironment().withProperty("schedule.sharing.enabled", "true"),
            ),
        )
        whenever(scheduleRepository.findScheduleList(memberId)).thenReturn(listOf(first, second))

        val result = securedService.getScheduleList(memberId)

        assertEquals(listOf(1L, 2L), result.map { it.id })
        assertTrue(result.all { it.canViewAllTravelPlans == true })
        verify(accessPolicy, never()).resolveAll(memberId, listOf(first, second))
    }

    @Test
    fun `global sharing off uses owner queries and filters a defensive foreign row`() {
        val memberId = 1L
        val owner = scheduleEntity(id = 1L, memberId = memberId)
        val retainedForeign = scheduleEntity(id = 2L, memberId = 99L)
        val disabledService = ScheduleService(
            scheduleRepository = scheduleRepository,
            objectMapper = objectMapper,
            subscriptionPolicyService = subscriptionPolicyService,
            sharingAvailabilityPolicy = ScheduleSharingAvailabilityPolicy(
                MockEnvironment().withProperty("schedule.sharing.enabled", "false"),
            ),
            transactionManager = calendarTransactionManager,
        )
        whenever(scheduleRepository.findOwnedScheduleList(memberId))
            .thenReturn(listOf(owner, retainedForeign))
        whenever(scheduleRepository.findOwnedScheduleDetail(1L, memberId)).thenReturn(owner)

        val list = disabledService.getScheduleList(memberId)
        val detail = disabledService.getScheduleDetail(memberId, 1L)

        assertEquals(listOf(1L), list.map { it.id })
        assertEquals(1L, detail.id)
        verify(scheduleRepository, never()).findScheduleList(memberId)
        verify(scheduleRepository, never()).findScheduleDetail(any(), any())
    }

    @Test
    fun `global sharing off uses an owner-only cache scope and filters defensive foreign rows`() {
        val memberId = 1L
        val rangeStart = Instant.parse("2026-06-01T00:00:00Z")
        val rangeEnd = Instant.parse("2026-06-30T23:59:59Z")
        val cacheService = mock<ScheduleCalendarCacheService>()
        val disabledService = ScheduleService(
            scheduleRepository = scheduleRepository,
            objectMapper = objectMapper,
            subscriptionPolicyService = subscriptionPolicyService,
            calendarCacheService = cacheService,
            sharingAvailabilityPolicy = ScheduleSharingAvailabilityPolicy(
                MockEnvironment().withProperty("schedule.sharing.enabled", "false"),
            ),
            transactionManager = calendarTransactionManager,
        )
        whenever(
            scheduleRepository.findOwnedOverlappingScheduleList(memberId, rangeStart, rangeEnd)
        ).thenReturn(
            listOf(
                scheduleEntity(id = 1L, memberId = memberId),
                scheduleEntity(id = 2L, memberId = 99L),
            )
        )
        whenever(
            cacheService.getOrLoad(
                eq(memberId),
                eq(ScheduleCalendarCacheScope.OWNED_ONLY),
                eq(rangeStart),
                eq(rangeEnd),
                any(),
            )
        ).thenAnswer { invocation ->
            val loader = invocation.getArgument<(Instant, Instant) -> List<ScheduleDto>>(4)
            loader(rangeStart, rangeEnd)
        }

        val result = disabledService.getCalendarScheduleList(
            memberId,
            rangeStart.toString(),
            rangeEnd.toString(),
        )

        assertEquals(listOf(1L), result.map { it.id })
        verify(cacheService).getOrLoad(
            eq(memberId),
            eq(ScheduleCalendarCacheScope.OWNED_ONLY),
            eq(rangeStart),
            eq(rangeEnd),
            any(),
        )
        verify(scheduleRepository, never()).findOverlappingScheduleList(any(), any(), any())
    }

    @Test
    fun `global sharing on uses the shared-visibility calendar cache scope`() {
        val memberId = 1L
        val rangeStart = Instant.parse("2026-06-01T00:00:00Z")
        val rangeEnd = Instant.parse("2026-06-30T23:59:59Z")
        val cacheService = mock<ScheduleCalendarCacheService>()
        val enabledService = ScheduleService(
            scheduleRepository = scheduleRepository,
            objectMapper = objectMapper,
            subscriptionPolicyService = subscriptionPolicyService,
            calendarCacheService = cacheService,
            sharingAvailabilityPolicy = ScheduleSharingAvailabilityPolicy(
                MockEnvironment().withProperty("schedule.sharing.enabled", "true"),
            ),
            transactionManager = calendarTransactionManager,
        )
        whenever(
            cacheService.getOrLoad(
                eq(memberId),
                eq(ScheduleCalendarCacheScope.SHARING_ENABLED),
                eq(rangeStart),
                eq(rangeEnd),
                any(),
            )
        ).thenReturn(emptyList())

        enabledService.getCalendarScheduleList(
            memberId,
            rangeStart.toString(),
            rangeEnd.toString(),
        )

        verify(cacheService).getOrLoad(
            eq(memberId),
            eq(ScheduleCalendarCacheScope.SHARING_ENABLED),
            eq(rangeStart),
            eq(rangeEnd),
            any(),
        )
        verify(scheduleRepository, never()).findOwnedOverlappingScheduleList(any(), any(), any())
    }

    @Test
    fun `calendar cache coordination stays outside the short read-only DB transaction`() {
        val memberId = 1L
        val rangeStart = Instant.parse("2026-06-01T00:00:00Z")
        val rangeEnd = Instant.parse("2026-06-30T23:59:59Z")
        val cacheService = mock<ScheduleCalendarCacheService>()
        val transactionManager = mock<PlatformTransactionManager>()
        val transactionStatus = mock<TransactionStatus>()
        var transactionActive = false

        whenever(transactionManager.getTransaction(any())).thenAnswer { invocation ->
            val definition = invocation.getArgument<TransactionDefinition>(0)
            assertTrue(definition.isReadOnly)
            assertFalse(transactionActive)
            transactionActive = true
            transactionStatus
        }
        doAnswer {
            assertTrue(transactionActive)
            transactionActive = false
            null
        }.whenever(transactionManager).commit(transactionStatus)
        whenever(
            scheduleRepository.findOverlappingScheduleList(memberId, rangeStart, rangeEnd)
        ).thenAnswer {
            assertTrue(transactionActive)
            emptyList<Schedule>()
        }
        whenever(
            cacheService.getOrLoad(
                eq(memberId),
                eq(ScheduleCalendarCacheScope.SHARING_ENABLED),
                eq(rangeStart),
                eq(rangeEnd),
                any(),
            )
        ).thenAnswer { invocation ->
            assertFalse(transactionActive)
            val loader = invocation.getArgument<(Instant, Instant) -> List<ScheduleDto>>(4)
            val result = loader(rangeStart, rangeEnd)
            assertFalse(transactionActive)
            result
        }
        val service = ScheduleService(
            scheduleRepository = scheduleRepository,
            objectMapper = objectMapper,
            subscriptionPolicyService = subscriptionPolicyService,
            calendarCacheService = cacheService,
            sharingAvailabilityPolicy = ScheduleSharingAvailabilityPolicy(
                MockEnvironment().withProperty("schedule.sharing.enabled", "true"),
            ),
            transactionManager = transactionManager,
        )

        val result = service.getCalendarScheduleList(
            memberId,
            rangeStart.toString(),
            rangeEnd.toString(),
        )

        assertTrue(result.isEmpty())
        assertFalse(transactionActive)
        verify(transactionManager).getTransaction(any())
        verify(transactionManager).commit(transactionStatus)
    }

    @Test
    fun `global sharing off rejects retained editor and foreign category or calendar mutations`() {
        val memberId = 1L
        val categoryRepository = mock<ScheduleCategoryRepository>()
        val categoryShares = mock<ScheduleCategoryShareRepository>()
        val calendars = mock<ScheduleCalendarRepository>()
        val calendarMembers = mock<ScheduleCalendarMemberRepository>()
        val disabledService = ScheduleService(
            scheduleRepository = scheduleRepository,
            objectMapper = objectMapper,
            subscriptionPolicyService = subscriptionPolicyService,
            categoryRepository = categoryRepository,
            categoryShareRepository = categoryShares,
            calendarRepository = calendars,
            calendarMemberRepository = calendarMembers,
            sharingAvailabilityPolicy = ScheduleSharingAvailabilityPolicy(
                MockEnvironment().withProperty("schedule.sharing.enabled", "false"),
            ),
        )
        whenever(categoryRepository.findById(1L)).thenReturn(
            Optional.of(ScheduleCategory(id = 1L, memberId = 99L, title = "dormant", color = "#000000")),
            Optional.of(ScheduleCategory(id = 1L, memberId = memberId, title = "owned", color = "#000000")),
        )

        val editorFailure = assertThrows<BusinessException> {
            disabledService.updateSchedule(memberId, 10L, scheduleDto())
        }
        val categoryFailure = assertThrows<BusinessException> {
            disabledService.addSchedule(memberId, scheduleDto())
        }
        val calendarFailure = assertThrows<BusinessException> {
            disabledService.addSchedule(
                memberId,
                scheduleDto().copy(calendarId = 30L),
            )
        }

        assertEquals(com.noLate.global.error.ErrorCode.SCHEDULE_NOT_FOUND, editorFailure.errorCode)
        assertEquals(com.noLate.global.error.ErrorCode.FEATURE_DISABLED, categoryFailure.errorCode)
        assertEquals(com.noLate.global.error.ErrorCode.FEATURE_DISABLED, calendarFailure.errorCode)
        verify(categoryShares, never()).findByCategoryIdAndTargetMemberId(any(), any())
        verify(calendars, never()).findAllForUpdate(any())
        verify(scheduleRepository, never()).save(any<Schedule>())
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
    fun `searchScheduleList normalizes blank filters and applies the default database limit`() {
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
                pageable = PageRequest.of(0, 20),
            )
        ).thenReturn(emptyList())

        // when
        val result = scheduleService.searchScheduleList(
            memberId = memberId,
            keyword = "   ",
            categoryId = "",
            startAt = startAt,
            endAt = endAt,
            limit = null,
        )

        // then
        assertEquals(0, result.size)
        verify(scheduleRepository, times(1)).searchScheduleList(
            memberId = memberId,
            keyword = null,
            categoryId = null,
            rangeStart = Instant.parse(startAt),
            rangeEnd = Instant.parse(endAt),
            pageable = PageRequest.of(0, 20),
        )
    }

    @Test
    fun `searchScheduleList trims filters and caps the shared database query at 50`() {
        whenever(
            scheduleRepository.searchScheduleList(
                memberId = 1L,
                keyword = "회의",
                categoryId = "12",
                rangeStart = null,
                rangeEnd = null,
                pageable = PageRequest.of(0, 50),
            )
        ).thenReturn(emptyList())

        scheduleService.searchScheduleList(
            memberId = 1L,
            keyword = "  회의  ",
            categoryId = " 12 ",
            startAt = null,
            endAt = null,
            limit = 500,
        )

        verify(scheduleRepository).searchScheduleList(
            memberId = 1L,
            keyword = "회의",
            categoryId = "12",
            rangeStart = null,
            rangeEnd = null,
            pageable = PageRequest.of(0, 50),
        )
    }

    @Test
    fun `searchScheduleList rejects a one character keyword before repository access`() {
        val error = assertThrows<BusinessException> {
            scheduleService.searchScheduleList(
                memberId = 1L,
                keyword = " 회 ",
                categoryId = null,
                startAt = null,
                endAt = null,
                limit = null,
            )
        }

        assertEquals(com.noLate.global.error.ErrorCode.INVALID_INPUT, error.errorCode)
        verify(scheduleRepository, never()).searchScheduleList(any(), any(), any(), any(), any(), any())
        verify(scheduleRepository, never()).searchOwnedScheduleList(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `searchScheduleList applies the minimum database limit to owned-only search`() {
        val ownedOnlyService = ScheduleService(
            scheduleRepository = scheduleRepository,
            objectMapper = objectMapper,
            subscriptionPolicyService = subscriptionPolicyService,
            sharingAvailabilityPolicy = ScheduleSharingAvailabilityPolicy(
                MockEnvironment().withProperty("schedule.sharing.enabled", "false"),
            ),
        )
        whenever(
            scheduleRepository.searchOwnedScheduleList(
                memberId = 1L,
                keyword = null,
                categoryId = null,
                rangeStart = null,
                rangeEnd = null,
                pageable = PageRequest.of(0, 1),
            )
        ).thenReturn(emptyList())

        ownedOnlyService.searchScheduleList(
            memberId = 1L,
            keyword = null,
            categoryId = null,
            startAt = null,
            endAt = null,
            limit = 0,
        )

        verify(scheduleRepository).searchOwnedScheduleList(
            memberId = 1L,
            keyword = null,
            categoryId = null,
            rangeStart = null,
            rangeEnd = null,
            pageable = PageRequest.of(0, 1),
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

    private class ImmediateTransactionManager : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus =
            SimpleTransactionStatus()

        override fun commit(status: TransactionStatus) = Unit

        override fun rollback(status: TransactionStatus) = Unit
    }
}
