package com.noLate.schedule.application.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleAlertMode
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.domain.ScheduleTravelPlanUpsertCommand
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import com.noLate.subscription.application.SubscriptionPolicyService
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class ScheduleTravelPlanServiceUnitTest {

    @Mock lateinit var scheduleRepository: ScheduleRepository
    @Mock lateinit var travelPlanRepository: ScheduleTravelPlanRepository
    @Mock lateinit var scheduleShareRepository: ScheduleShareRepository
    @Mock lateinit var categoryShareRepository: ScheduleCategoryShareRepository
    @Mock lateinit var memberRepository: MemberRepository
    @Mock lateinit var subscriptionPolicyService: SubscriptionPolicyService
    @Mock lateinit var entityManager: EntityManager

    private lateinit var service: ScheduleTravelPlanService

    @BeforeEach
    fun setUp() {
        service = ScheduleTravelPlanService(
            scheduleRepository = scheduleRepository,
            travelPlanRepository = travelPlanRepository,
            scheduleShareRepository = scheduleShareRepository,
            categoryShareRepository = categoryShareRepository,
            memberRepository = memberRepository,
            subscriptionPolicyService = subscriptionPolicyService,
            objectMapper = jacksonObjectMapper(),
            entityManager = entityManager,
        )
    }

    @Test
    fun `shared participant stores a route owned only by that participant`() {
        val schedule = scheduleEntity()
        whenever(scheduleRepository.findScheduleDetail(10L, 2L)).thenReturn(schedule)
        whenever(scheduleRepository.findActiveForTravelPlanUpdate(10L)).thenReturn(schedule)
        whenever(travelPlanRepository.findByScheduleIdAndMemberIdForUpdate(10L, 2L)).thenReturn(null)
        whenever(travelPlanRepository.saveAndFlush(any<ScheduleTravelPlan>()))
            .thenAnswer { it.getArgument(0) }

        val result = service.upsertMyTravelPlan(
            memberId = 2L,
            scheduleId = 10L,
            command = routeCommand(originName = "참여자 집", travelMinutes = 28)
                .copy(alertMode = ScheduleAlertMode.ALARM),
        )

        verify(travelPlanRepository).saveAndFlush(check {
            assertEquals(10L, it.scheduleId)
            assertEquals(2L, it.memberId)
            assertEquals("참여자 집", it.originName)
            assertEquals(28, it.travelMinutes)
            assertEquals(ScheduleAlertMode.ALARM, it.alertMode)
        })
        assertEquals("참여자 집", result.origin?.name)
        assertEquals("강남역", result.destination?.name)
        assertEquals(2L, result.memberId)
        assertEquals(ScheduleAlertMode.ALARM, result.alertMode)
    }

    @Test
    fun `personal alarm mode is stored and a legacy update preserves it`() {
        val schedule = scheduleEntity()
        val existing = travelPlan(memberId = 2L, originName = "참여자 집").apply {
            alertMode = ScheduleAlertMode.ALARM
        }
        whenever(scheduleRepository.findScheduleDetail(10L, 2L)).thenReturn(schedule)
        whenever(scheduleRepository.findActiveForTravelPlanUpdate(10L)).thenReturn(schedule)
        whenever(travelPlanRepository.findByScheduleIdAndMemberIdForUpdate(10L, 2L)).thenReturn(existing)
        whenever(travelPlanRepository.saveAndFlush(existing)).thenReturn(existing)

        val result = service.upsertMyTravelPlan(
            memberId = 2L,
            scheduleId = 10L,
            command = routeCommand(originName = "새 출발지", travelMinutes = 31),
        )

        assertEquals(ScheduleAlertMode.ALARM, existing.alertMode)
        assertEquals(ScheduleAlertMode.ALARM, result.alertMode)
    }

    @Test
    fun `empty personal plan is rejected instead of being marked ready`() {
        val schedule = scheduleEntity()
        whenever(scheduleRepository.findScheduleDetail(10L, 2L)).thenReturn(schedule)
        whenever(scheduleRepository.findActiveForTravelPlanUpdate(10L)).thenReturn(schedule)

        val error = assertThrows(BusinessException::class.java) {
            service.upsertMyTravelPlan(
                memberId = 2L,
                scheduleId = 10L,
                command = ScheduleTravelPlanUpsertCommand(),
            )
        }

        assertEquals(ErrorCode.INVALID_INPUT, error.errorCode)
        verify(travelPlanRepository, never()).saveAndFlush(any<ScheduleTravelPlan>())
    }

    @Test
    fun `personal plan rejects a partial origin coordinate pair`() {
        val schedule = scheduleEntity()
        whenever(scheduleRepository.findScheduleDetail(10L, 2L)).thenReturn(schedule)
        whenever(scheduleRepository.findActiveForTravelPlanUpdate(10L)).thenReturn(schedule)

        val error = assertThrows(BusinessException::class.java) {
            service.upsertMyTravelPlan(
                memberId = 2L,
                scheduleId = 10L,
                command = routeCommand(originName = "참가자 집", travelMinutes = 28).copy(
                    originLng = null,
                ),
            )
        }

        assertEquals(ErrorCode.INVALID_INPUT, error.errorCode)
        verify(travelPlanRepository, never()).saveAndFlush(any<ScheduleTravelPlan>())
    }

    @Test
    fun `personal plan rejects non finite and out of range origin coordinates`() {
        listOf(
            Double.NaN to 127.0,
            37.5 to Double.POSITIVE_INFINITY,
            -91.0 to 127.0,
            37.5 to 181.0,
        ).forEach { (lat, lng) ->
            val schedule = scheduleEntity()
            whenever(scheduleRepository.findScheduleDetail(10L, 2L)).thenReturn(schedule)
            whenever(scheduleRepository.findActiveForTravelPlanUpdate(10L)).thenReturn(schedule)

            val error = assertThrows(BusinessException::class.java) {
                service.upsertMyTravelPlan(
                    memberId = 2L,
                    scheduleId = 10L,
                    command = routeCommand(originName = "참가자 집", travelMinutes = 28).copy(
                        originLat = lat,
                        originLng = lng,
                    ),
                )
            }

            assertEquals(ErrorCode.INVALID_INPUT, error.errorCode)
        }
        verify(travelPlanRepository, never()).saveAndFlush(any<ScheduleTravelPlan>())
    }

    @Test
    fun `invisible schedule is rejected before acquiring its mutation lock`() {
        whenever(scheduleRepository.findScheduleDetail(10L, 99L)).thenReturn(null)

        val error = assertThrows(BusinessException::class.java) {
            service.upsertMyTravelPlan(
                memberId = 99L,
                scheduleId = 10L,
                command = routeCommand(originName = "알 수 없는 사용자", travelMinutes = 30),
            )
        }

        assertEquals(ErrorCode.SCHEDULE_NOT_FOUND, error.errorCode)
        verify(scheduleRepository, never()).findActiveForTravelPlanUpdate(10L)
        verifyNoInteractions(entityManager)
        verifyNoInteractions(travelPlanRepository)
    }

    @Test
    fun `owner supplements missing common destination coordinates without changing route meaning`() {
        val schedule = scheduleEntity()
        val commonRoute = requireNotNull(schedule.route).apply {
            destinationLat = null
            destinationLng = null
            // 빠른 일정에서 생성된 이름 중심 도착지를 재현한다.
            destinationAddress = null
        }
        val originalDestinationName = commonRoute.destinationName
        val originalDestinationAddress = commonRoute.destinationAddress
        val originalOriginName = commonRoute.originName
        val originalRouteJson = commonRoute.routeJson
        val originalNotificationEnabled = commonRoute.notificationEnabled
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(scheduleRepository.findActiveForTravelPlanUpdate(10L)).thenReturn(schedule)
        whenever(travelPlanRepository.findByScheduleIdAndMemberIdForUpdate(10L, 1L)).thenReturn(null)
        whenever(travelPlanRepository.saveAndFlush(any<ScheduleTravelPlan>()))
            .thenAnswer { it.getArgument(0) }

        val result = service.upsertMyTravelPlan(
            memberId = 1L,
            scheduleId = 10L,
            command = routeCommand(originName = "오너 새 출발지", travelMinutes = 31).copy(
                // TMAP `강남역` POI 검색의 실제 첫 결과 형식과 접근 좌표다.
                destinationName = "강남역[2호선] 2번 출구",
                destinationAddress = "서울 강남구 강남대로 지하 396",
                destinationLat = 37.49812971,
                destinationLng = 127.02868505,
            ),
        )

        assertEquals(37.49812971, commonRoute.destinationLat)
        assertEquals(127.02868505, commonRoute.destinationLng)
        assertEquals(originalDestinationName, commonRoute.destinationName)
        assertEquals(originalDestinationAddress, commonRoute.destinationAddress)
        assertEquals(originalOriginName, commonRoute.originName)
        assertEquals(originalRouteJson, commonRoute.routeJson)
        assertEquals(originalNotificationEnabled, commonRoute.notificationEnabled)
        assertEquals(37.49812971, result.destination?.lat)
        assertEquals(127.02868505, result.destination?.lng)
    }

    @Test
    fun `viewer cannot supplement missing common destination coordinates`() {
        val schedule = scheduleEntity().apply {
            requireNotNull(route).destinationLat = null
            requireNotNull(route).destinationLng = null
        }
        whenever(scheduleRepository.findScheduleDetail(10L, 2L)).thenReturn(schedule)
        whenever(scheduleRepository.findActiveForTravelPlanUpdate(10L)).thenReturn(schedule)
        whenever(scheduleShareRepository.findByScheduleIdAndTargetMemberId(10L, 2L))
            .thenReturn(scheduleShare(targetMemberId = 2L, permission = ScheduleSharePermission.VIEWER))

        val error = assertThrows(BusinessException::class.java) {
            service.upsertMyTravelPlan(
                memberId = 2L,
                scheduleId = 10L,
                command = routeCommand(originName = "참여자 집", travelMinutes = 28).copy(
                    destinationName = "강남역",
                    destinationLat = 37.497,
                    destinationLng = 127.027,
                ),
            )
        }

        assertEquals(ErrorCode.FORBIDDEN, error.errorCode)
        assertNull(schedule.route?.destinationLat)
        assertNull(schedule.route?.destinationLng)
        verify(travelPlanRepository, never()).saveAndFlush(any<ScheduleTravelPlan>())
    }

    @Test
    fun `complete common destination accepts a nearby provider point without overwriting it`() {
        val schedule = scheduleEntity()
        val commonRoute = requireNotNull(schedule.route)
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(scheduleRepository.findActiveForTravelPlanUpdate(10L)).thenReturn(schedule)
        whenever(travelPlanRepository.findByScheduleIdAndMemberIdForUpdate(10L, 1L)).thenReturn(null)
        whenever(travelPlanRepository.saveAndFlush(any<ScheduleTravelPlan>()))
            .thenAnswer { it.getArgument(0) }

        service.upsertMyTravelPlan(
            memberId = 1L,
            scheduleId = 10L,
            command = routeCommand(originName = "오너 집", travelMinutes = 30).copy(
                // 이름 표기는 provider마다 달라도 좌표가 같은 역 반경이면 허용한다.
                destinationName = "Gangnam Station Exit 2",
                destinationLat = 37.49812971,
                destinationLng = 127.02868505,
            ),
        )

        assertEquals(37.497, commonRoute.destinationLat)
        assertEquals(127.027, commonRoute.destinationLng)
    }

    @Test
    fun `complete common destination rejects a far personal route destination`() {
        val schedule = scheduleEntity()
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(scheduleRepository.findActiveForTravelPlanUpdate(10L)).thenReturn(schedule)

        val error = assertThrows(BusinessException::class.java) {
            service.upsertMyTravelPlan(
                memberId = 1L,
                scheduleId = 10L,
                command = routeCommand(originName = "오너 집", travelMinutes = 30).copy(
                    destinationName = "부산역",
                    destinationLat = 35.1151,
                    destinationLng = 129.0414,
                ),
            )
        }

        assertEquals(ErrorCode.INVALID_INPUT, error.errorCode)
        assertEquals(37.497, schedule.route?.destinationLat)
        assertEquals(127.027, schedule.route?.destinationLng)
        verify(travelPlanRepository, never()).saveAndFlush(any<ScheduleTravelPlan>())
    }

    @Test
    fun `antipodal destination cannot bypass the distance check through floating point overflow`() {
        val schedule = scheduleEntity().apply {
            requireNotNull(route).destinationLat = 38.12502807359892
            requireNotNull(route).destinationLng = 130.25502621933367
        }
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(scheduleRepository.findActiveForTravelPlanUpdate(10L)).thenReturn(schedule)

        val error = assertThrows(BusinessException::class.java) {
            service.upsertMyTravelPlan(
                memberId = 1L,
                scheduleId = 10L,
                command = routeCommand(originName = "오너 집", travelMinutes = 30).copy(
                    destinationName = "지구 반대편",
                    destinationLat = -38.125028075218566,
                    destinationLng = -49.74497346469494,
                ),
            )
        }

        assertEquals(ErrorCode.INVALID_INPUT, error.errorCode)
        verify(travelPlanRepository, never()).saveAndFlush(any<ScheduleTravelPlan>())
    }

    @Test
    fun `locked refresh replaces a stale visible route before destination validation and fingerprinting`() {
        val schedule = scheduleEntity()
        val route = requireNotNull(schedule.route).apply {
            destinationLat = null
            destinationLng = null
            originName = "오래된 오너 출발지"
            routeJson = "{\"stale\":true}"
        }
        whenever(scheduleRepository.findActiveForTravelPlanUpdate(10L)).thenReturn(schedule)
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        doAnswer {
            // 선행 transaction이 schedule lock 대기 중 commit한 최신 공통 원본을 흉내 낸다.
            route.destinationLat = 37.497
            route.destinationLng = 127.027
            route.originName = "최신 오너 출발지"
            route.routeJson = "{\"latest\":true}"
            null
        }.whenever(entityManager).refresh(schedule, LockModeType.PESSIMISTIC_WRITE)
        whenever(travelPlanRepository.findByScheduleIdAndMemberIdForUpdate(10L, 1L)).thenReturn(null)
        whenever(travelPlanRepository.saveAndFlush(any<ScheduleTravelPlan>()))
            .thenAnswer { it.getArgument(0) }

        service.upsertMyTravelPlan(
            memberId = 1L,
            scheduleId = 10L,
            command = routeCommand(originName = "새 개인 출발지", travelMinutes = 30).copy(
                destinationName = "강남역[2호선]",
                destinationLat = 37.49812971,
                destinationLng = 127.02868505,
            ),
        )

        assertEquals(37.497, route.destinationLat)
        assertEquals(127.027, route.destinationLng)
        assertEquals("최신 오너 출발지", route.originName)
        assertEquals("{\"latest\":true}", route.routeJson)
        inOrder(scheduleRepository, entityManager).apply {
            verify(scheduleRepository).findScheduleDetail(10L, 1L)
            verify(scheduleRepository).findActiveForTravelPlanUpdate(10L)
            verify(entityManager).refresh(schedule, LockModeType.PESSIMISTIC_WRITE)
        }
    }

    @Test
    fun `common destination supplement rejects a legacy partial coordinate`() {
        val schedule = scheduleEntity().apply {
            requireNotNull(route).destinationLng = null
        }
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(scheduleRepository.findActiveForTravelPlanUpdate(10L)).thenReturn(schedule)

        val error = assertThrows(BusinessException::class.java) {
            service.upsertMyTravelPlan(
                memberId = 1L,
                scheduleId = 10L,
                command = routeCommand(originName = "오너 집", travelMinutes = 30).copy(
                    destinationName = "강남역",
                    destinationLat = 37.498,
                    destinationLng = 127.028,
                ),
            )
        }

        assertEquals(ErrorCode.INVALID_STATE, error.errorCode)
        assertEquals(37.497, schedule.route?.destinationLat)
        assertNull(schedule.route?.destinationLng)
        verify(travelPlanRepository, never()).saveAndFlush(any<ScheduleTravelPlan>())
    }

    @Test
    fun `common destination supplement rejects when another active personal plan exists`() {
        val schedule = scheduleEntity().apply {
            requireNotNull(route).destinationLat = null
            requireNotNull(route).destinationLng = null
        }
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(scheduleRepository.findActiveForTravelPlanUpdate(10L)).thenReturn(schedule)
        whenever(travelPlanRepository.findAllActiveForScheduleUpdate(10L))
            .thenReturn(listOf(travelPlan(memberId = 2L, originName = "참여자 집")))

        val error = assertThrows(BusinessException::class.java) {
            service.upsertMyTravelPlan(
                memberId = 1L,
                scheduleId = 10L,
                command = routeCommand(originName = "오너 집", travelMinutes = 30).copy(
                    destinationName = "강남역",
                    destinationLat = 37.498,
                    destinationLng = 127.028,
                ),
            )
        }

        assertEquals(ErrorCode.INVALID_STATE, error.errorCode)
        assertNull(schedule.route?.destinationLat)
        assertNull(schedule.route?.destinationLng)
        verify(travelPlanRepository, never()).saveAndFlush(any<ScheduleTravelPlan>())
    }

    @Test
    fun `common destination supplement rejects a different place identity`() {
        val schedule = scheduleEntity().apply {
            requireNotNull(route).destinationLat = null
            requireNotNull(route).destinationLng = null
        }
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(scheduleRepository.findActiveForTravelPlanUpdate(10L)).thenReturn(schedule)

        val error = assertThrows(BusinessException::class.java) {
            service.upsertMyTravelPlan(
                memberId = 1L,
                scheduleId = 10L,
                command = routeCommand(originName = "오너 집", travelMinutes = 30).copy(
                    destinationName = "강남역센트럴푸르지오시티",
                    destinationLat = 37.49787975,
                    destinationLng = 127.02951832,
                ),
            )
        }

        assertEquals(ErrorCode.INVALID_INPUT, error.errorCode)
        assertNull(schedule.route?.destinationLat)
        assertNull(schedule.route?.destinationLng)
        verify(travelPlanRepository, never()).saveAndFlush(any<ScheduleTravelPlan>())
    }

    @Test
    fun `common destination supplement rejects the same name at a different address`() {
        val schedule = scheduleEntity().apply {
            requireNotNull(route).destinationName = "우리집"
            requireNotNull(route).destinationAddress = "서울특별시 마포구 월드컵북로 1"
            requireNotNull(route).destinationLat = null
            requireNotNull(route).destinationLng = null
        }
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(scheduleRepository.findActiveForTravelPlanUpdate(10L)).thenReturn(schedule)

        val error = assertThrows(BusinessException::class.java) {
            service.upsertMyTravelPlan(
                memberId = 1L,
                scheduleId = 10L,
                command = routeCommand(originName = "오너 집", travelMinutes = 30).copy(
                    destinationName = "우리집",
                    destinationAddress = "부산광역시 해운대구 해운대해변로 1",
                    destinationLat = 35.1587,
                    destinationLng = 129.1604,
                ),
            )
        }

        assertEquals(ErrorCode.INVALID_INPUT, error.errorCode)
        assertNull(schedule.route?.destinationLat)
        assertNull(schedule.route?.destinationLng)
        verify(travelPlanRepository, never()).saveAndFlush(any<ScheduleTravelPlan>())
    }

    @Test
    fun `common destination supplement rejects out of range coordinates`() {
        val schedule = scheduleEntity().apply {
            requireNotNull(route).destinationLat = null
            requireNotNull(route).destinationLng = null
        }
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(scheduleRepository.findActiveForTravelPlanUpdate(10L)).thenReturn(schedule)

        val error = assertThrows(BusinessException::class.java) {
            service.upsertMyTravelPlan(
                memberId = 1L,
                scheduleId = 10L,
                command = routeCommand(originName = "오너 집", travelMinutes = 30).copy(
                    destinationName = "강남역",
                    destinationLat = 91.0,
                    destinationLng = 127.027,
                ),
            )
        }

        assertEquals(ErrorCode.INVALID_INPUT, error.errorCode)
        assertNull(schedule.route?.destinationLat)
        assertNull(schedule.route?.destinationLng)
        verify(travelPlanRepository, never()).saveAndFlush(any<ScheduleTravelPlan>())
    }

    @Test
    fun `global off uses owner detail query and hides dormant participant overview`() {
        val accessPolicy = mock<ScheduleAccessPolicy>()
        val ownerOnlyService = ScheduleTravelPlanService(
            scheduleRepository = scheduleRepository,
            travelPlanRepository = travelPlanRepository,
            scheduleShareRepository = scheduleShareRepository,
            categoryShareRepository = categoryShareRepository,
            memberRepository = memberRepository,
            subscriptionPolicyService = subscriptionPolicyService,
            objectMapper = jacksonObjectMapper(),
            entityManager = entityManager,
            scheduleAccessPolicy = accessPolicy,
        )
        whenever(accessPolicy.isSharingDisabled()).thenReturn(true)
        whenever(scheduleRepository.findOwnedScheduleDetail(10L, 2L)).thenReturn(null)

        val error = assertThrows(BusinessException::class.java) {
            ownerOnlyService.getOverview(requesterMemberId = 2L, scheduleId = 10L)
        }

        assertEquals(ErrorCode.SCHEDULE_NOT_FOUND, error.errorCode)
        verify(scheduleRepository).findOwnedScheduleDetail(10L, 2L)
        verify(scheduleRepository, never()).findScheduleDetail(10L, 2L)
        verifyNoInteractions(travelPlanRepository)
    }

    @Test
    fun `participant without a plan keeps common destination without seeing owner route`() {
        val schedule = scheduleEntity()
        val ownerView = schedule.toDto(jacksonObjectMapper())

        val participantView = service.personalizeScheduleDto(
            memberId = 2L,
            schedule = schedule,
            base = ownerView,
            plan = null,
        )

        // 공통 목적지는 새 개인 경로를 계산하는 데 필요하지만, 출발지부터 알림까지는
        // 오너 개인 정보이므로 공유받은 사용자의 평탄형 응답에서 반드시 제거한다.
        assertEquals("강남역", participantView.destination?.name)
        assertNull(participantView.origin)
        assertNull(participantView.route)
        assertNull(participantView.travelMinutes)
        assertNull(participantView.departAt)
        assertNull(participantView.travelMode)
        assertFalse(participantView.notificationEnabled ?: true)
        assertEquals(ScheduleAlertMode.STANDARD, participantView.alertMode)
        assertTrue(participantView.routeSetupRequired == true)
    }

    @Test
    fun `removing owner route retires mirrored owner travel plan`() {
        val schedule = scheduleEntity().apply {
            updateRoute(
                travelMinutes = null,
                departAt = null,
                departedAt = null,
                travelMode = null,
                locationName = null,
                originName = null,
                originAddress = null,
                originLat = null,
                originLng = null,
                destinationName = null,
                destinationAddress = null,
                destinationLat = null,
                destinationLng = null,
                routeJson = null,
                notificationEnabled = false,
                notificationLeadMinutes = null,
                notificationIntervalMinutes = null,
            )
        }
        val mirrored = travelPlan(memberId = 1L, originName = "오너 집")
        whenever(scheduleRepository.findActiveForTravelPlanUpdate(10L)).thenReturn(schedule)
        whenever(travelPlanRepository.findByScheduleIdAndMemberIdForUpdate(10L, 1L)).thenReturn(mirrored)

        val result = service.syncOwnerTravelPlan(
            memberId = 1L,
            scheduleDto = schedule.toDto(jacksonObjectMapper()),
        )

        assertNull(result)
        assertTrue(mirrored.deleted)
        assertTrue(mirrored.deletedAt != null)
    }

    @Test
    fun `travel mode alone retires mirrored owner travel plan`() {
        val schedule = scheduleEntity()
        val mirrored = travelPlan(memberId = 1L, originName = "오너 집")
        whenever(scheduleRepository.findActiveForTravelPlanUpdate(10L)).thenReturn(schedule)
        whenever(travelPlanRepository.findByScheduleIdAndMemberIdForUpdate(10L, 1L)).thenReturn(mirrored)
        val modeOnlyDto = schedule.toDto(jacksonObjectMapper()).copy(
            travelMinutes = null,
            departAt = null,
            travelMode = ScheduleTravelMode.CAR,
            origin = null,
            route = null,
            notificationEnabled = false,
            notificationLeadMinutes = null,
            notificationIntervalMinutes = null,
        )

        val result = service.syncOwnerTravelPlan(
            memberId = 1L,
            scheduleDto = modeOnlyDto,
        )

        assertNull(result)
        assertTrue(mirrored.deleted)
        verify(travelPlanRepository, never()).saveAndFlush(any<ScheduleTravelPlan>())
    }

    @Test
    fun `editor can read another participants full saved travel plan`() {
        val schedule = scheduleEntity()
        val targetPlan = travelPlan(memberId = 2L, originName = "참여자 집")
        whenever(scheduleRepository.findScheduleDetail(10L, 3L)).thenReturn(schedule)
        whenever(scheduleShareRepository.findByScheduleIdAndTargetMemberId(10L, 3L))
            .thenReturn(scheduleShare(targetMemberId = 3L, permission = ScheduleSharePermission.EDITOR))
        whenever(
            scheduleShareRepository.findAllByScheduleIdAndStatusAndDeletedFalseOrderByIdAsc(
                10L,
                com.noLate.schedule.domain.ScheduleShareStatus.ACTIVE,
            )
        ).thenReturn(
            listOf(
                scheduleShare(targetMemberId = 2L, permission = ScheduleSharePermission.VIEWER),
                scheduleShare(targetMemberId = 3L, permission = ScheduleSharePermission.EDITOR),
            )
        )
        whenever(travelPlanRepository.findByScheduleIdAndMemberIdAndDeletedFalse(10L, 2L))
            .thenReturn(targetPlan)

        val result = service.getTravelPlan(
            requesterMemberId = 3L,
            scheduleId = 10L,
            targetMemberId = 2L,
        )

        assertEquals("참여자 집", result.origin?.name)
        assertEquals(2L, result.memberId)
    }

    @Test
    fun `viewer cannot read another participants full saved travel plan`() {
        val schedule = scheduleEntity()
        whenever(scheduleRepository.findScheduleDetail(10L, 3L)).thenReturn(schedule)
        whenever(scheduleShareRepository.findByScheduleIdAndTargetMemberId(10L, 3L))
            .thenReturn(scheduleShare(targetMemberId = 3L, permission = ScheduleSharePermission.VIEWER))

        val error = assertThrows(BusinessException::class.java) {
            service.getTravelPlan(
                requesterMemberId = 3L,
                scheduleId = 10L,
                targetMemberId = 2L,
            )
        }

        assertEquals(ErrorCode.FORBIDDEN, error.errorCode)
    }

    @Test
    fun `owner can read another participants full saved travel plan`() {
        val schedule = scheduleEntity()
        whenever(scheduleRepository.findScheduleDetail(10L, 1L)).thenReturn(schedule)
        whenever(
            scheduleShareRepository.findAllByScheduleIdAndStatusAndDeletedFalseOrderByIdAsc(
                10L,
                com.noLate.schedule.domain.ScheduleShareStatus.ACTIVE,
            )
        ).thenReturn(listOf(scheduleShare(targetMemberId = 2L, permission = ScheduleSharePermission.VIEWER)))
        whenever(travelPlanRepository.findByScheduleIdAndMemberIdAndDeletedFalse(10L, 2L))
            .thenReturn(travelPlan(memberId = 2L, originName = "회사"))

        val result = service.getTravelPlan(1L, 10L, 2L)

        assertTrue(result.canManageSchedule)
        assertEquals("회사", result.origin?.name)
    }

    private fun scheduleEntity(): Schedule = Schedule(
        id = 10L,
        memberId = 1L,
        title = "공유 미팅",
        startAt = Instant.parse("2026-07-20T01:00:00Z"),
        endAt = Instant.parse("2026-07-20T02:00:00Z"),
    ).apply {
        updateCategorySnapshot("5", "프로젝트", "#2979FF")
        updateRoute(
            travelMinutes = 40,
            departAt = Instant.parse("2026-07-20T00:20:00Z"),
            departedAt = null,
            travelMode = ScheduleTravelMode.TRANSIT,
            locationName = "강남역",
            originName = "오너 집",
            originAddress = "서울시 은평구",
            originLat = 37.6,
            originLng = 126.9,
            destinationName = "강남역",
            destinationAddress = "서울시 강남구",
            destinationLat = 37.497,
            destinationLng = 127.027,
            routeJson = "{\"id\":\"owner-route\"}",
            notificationEnabled = false,
            notificationLeadMinutes = null,
            notificationIntervalMinutes = null,
        )
    }

    private fun routeCommand(originName: String, travelMinutes: Int) = ScheduleTravelPlanUpsertCommand(
        travelMinutes = travelMinutes,
        departAt = "2026-07-20T00:32:00Z",
        travelMode = ScheduleTravelMode.CAR,
        originName = originName,
        originAddress = "서울시 마포구",
        originLat = 37.55,
        originLng = 126.91,
        routeJson = "{\"id\":\"target-route\"}",
        notificationEnabled = false,
    )

    private fun travelPlan(memberId: Long, originName: String): ScheduleTravelPlan =
        ScheduleTravelPlan(scheduleId = 10L, memberId = memberId).apply {
            replace(
                command = routeCommand(originName, 28),
                scheduleFingerprint = "fingerprint",
                departAt = Instant.parse("2026-07-20T00:32:00Z"),
                routeJson = "{\"id\":\"target-route\"}",
                notificationLeadMinutes = null,
                notificationIntervalMinutes = null,
            )
        }

    private fun scheduleShare(
        targetMemberId: Long,
        permission: ScheduleSharePermission,
    ) = ScheduleShare(
        scheduleId = 10L,
        ownerMemberId = 1L,
        targetMemberId = targetMemberId,
        permission = permission,
    )
}
