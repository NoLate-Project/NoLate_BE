package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.notification.support.ensureActivePushMember
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleNotificationInputFingerprint
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.domain.ScheduleTravelPlanDto
import com.noLate.schedule.domain.ScheduleTravelPlanFingerprint
import com.noLate.schedule.domain.ScheduleTravelPlanStatus
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@DataJpaTest
@Import(
    SchedulePushJobBackfillCandidateReader::class,
    SchedulePushJobBackfillPairWriter::class,
    SchedulePushJobBackfill::class,
    SchedulePushJobService::class,
    SchedulePushJobBackfillTestConfig::class,
)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:schedule-push-backfill;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ],
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SchedulePushJobBackfillIntegrationTest @Autowired constructor(
    private val scheduleRepository: ScheduleRepository,
    private val travelPlanRepository: ScheduleTravelPlanRepository,
    private val pushJobRepository: SchedulePushJobRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val backfill: SchedulePushJobBackfill,
    private val service: SchedulePushJobService,
    private val objectMapper: ObjectMapper,
    private val transactionManager: PlatformTransactionManager,
) {
    private val now = Instant.parse("2026-07-24T00:00:00Z")

    @Test
    fun `legacy drain rebuilds exact owner and current participant pairs with runtime fingerprints`() {
        listOf(1L, 2L, 3L, 4L, 5L, 6L, 10L, 11L, 20L, 21L).forEach {
            ensureActivePushMember(jdbcTemplate, it)
        }
        val schedule = schedule(
            ownerMemberId = 1L,
            title = "공유 회의",
            startAt = now.plusSeconds(7_200),
        )
        scheduleRepository.saveAndFlush(schedule)
        val scheduleId = requireNotNull(schedule.id)
        val pastSchedule = schedule(
            ownerMemberId = 10L,
            title = "이미 끝난 일정",
            startAt = now.minusSeconds(60),
        )
        val deletedSchedule = schedule(
            ownerMemberId = 20L,
            title = "삭제된 일정",
            startAt = now.plusSeconds(10_800),
        ).apply {
            softDelete()
        }
        scheduleRepository.saveAllAndFlush(listOf(pastSchedule, deletedSchedule))

        val currentParticipant = plan(schedule, memberId = 2L)
        val staleParticipant = plan(schedule, memberId = 3L).apply {
            scheduleFingerprint = "stale-schedule-fingerprint"
        }
        val existingParticipant = plan(schedule, memberId = 4L)
        val disabledParticipant = plan(schedule, memberId = 5L).apply {
            notificationEnabled = false
        }
        val deletedParticipant = plan(schedule, memberId = 6L).apply {
            softDelete()
        }
        travelPlanRepository.saveAll(
            listOf(
                currentParticipant,
                staleParticipant,
                existingParticipant,
                disabledParticipant,
                deletedParticipant,
                plan(pastSchedule, memberId = 11L),
                plan(deletedSchedule, memberId = 21L),
            ),
        )
        pushJobRepository.saveAndFlush(existingJob(scheduleId, memberId = 4L))

        backfill.registerMissingJobs()

        val jobs = pushJobRepository.findAllByScheduleId(scheduleId)
            .associateBy(SchedulePushJob::memberId)
        // A participant job for this schedule must not hide the missing owner pair.
        assertEquals(setOf(1L, 2L, 4L), jobs.keys)
        assertFalse(jobs.containsKey(3L), "stale personal plans must not be rebuilt")
        assertFalse(jobs.containsKey(5L), "disabled personal plans must not be rebuilt")
        assertFalse(jobs.containsKey(6L), "deleted personal plans must not be rebuilt")
        assertEquals(
            emptyList<SchedulePushJob>(),
            pushJobRepository.findAllByScheduleId(requireNotNull(pastSchedule.id)),
        )
        assertEquals(
            emptyList<SchedulePushJob>(),
            pushJobRepository.findAllByScheduleId(requireNotNull(deletedSchedule.id)),
        )

        val scheduleDto = schedule.toDto(objectMapper)
        assertEquals(
            ScheduleNotificationInputFingerprint.fromSchedule(1L, scheduleDto),
            jobs.getValue(1L).notificationInputFingerprint,
        )
        assertEquals(
            ScheduleNotificationInputFingerprint.fromTravelPlan(
                memberId = 2L,
                schedule = scheduleDto,
                plan = currentParticipant.toExpectedDto(schedule),
            ),
            jobs.getValue(2L).notificationInputFingerprint,
        )

        // The first identical PUT after the controlled legacy drain must not look like a semantic
        // migration edit. Existing progress survives; an actual notification-title edit opens the
        // next generation and resets only then.
        TransactionTemplate(transactionManager).executeWithoutResult {
            requireNotNull(
                pushJobRepository.findByScheduleIdAndMemberIdForUpdate(scheduleId, 1L),
            ).apply {
                startProcessing("post-backfill-test", now)
                finishCheck(
                    travelMinutes = 30,
                    recommendedDepartureAt = schedule.startAt.minusSeconds(1_800),
                    pushSent = false,
                    notifiedDepartureAt = null,
                    nextCheckAt = now.plusSeconds(60),
                    completeAfterCheck = false,
                    now = now,
                )
            }
            pushJobRepository.flush()
        }

        service.registerFromScheduleDto(1L, scheduleDto)
        val afterNoOp = requireNotNull(
            pushJobRepository.findByScheduleIdAndMemberId(scheduleId, 1L),
        )
        assertEquals(0L, afterNoOp.notificationGeneration)
        assertEquals(1, afterNoOp.checkCount)

        service.registerFromScheduleDto(
            1L,
            scheduleDto.copy(title = "실제 알림 의미가 바뀐 회의"),
        )
        val afterMeaningfulEdit = requireNotNull(
            pushJobRepository.findByScheduleIdAndMemberId(scheduleId, 1L),
        )
        assertEquals(1L, afterMeaningfulEdit.notificationGeneration)
        assertEquals(0, afterMeaningfulEdit.checkCount)
    }

    private fun schedule(
        ownerMemberId: Long,
        title: String,
        startAt: Instant,
    ): Schedule =
        Schedule(
            memberId = ownerMemberId,
            title = title,
            startAt = startAt,
            endAt = startAt.plusSeconds(3_600),
        ).apply {
            updateCategorySnapshot("1", "업무", "#000000")
            updateRoute(
                travelMinutes = 30,
                departAt = startAt.minusSeconds(1_800),
                departedAt = null,
                travelMode = ScheduleTravelMode.CAR,
                locationName = "회의실",
                originName = "집",
                originAddress = "서울 출발지",
                originLat = 37.1,
                originLng = 127.1,
                destinationName = "회사",
                destinationAddress = "서울 도착지",
                destinationLat = 37.2,
                destinationLng = 127.2,
                routeJson = """{"path":[1,2],"summary":"owner"}""",
                notificationEnabled = true,
                notificationLeadMinutes = 60,
                notificationIntervalMinutes = 20,
            )
        }

    private fun plan(
        schedule: Schedule,
        memberId: Long,
    ): ScheduleTravelPlan =
        ScheduleTravelPlan(
            scheduleId = requireNotNull(schedule.id),
            memberId = memberId,
            travelMinutes = 40,
            departAt = schedule.startAt.minusSeconds(2_400),
            travelMode = ScheduleTravelMode.TRANSIT,
            originName = "참가자 집",
            originAddress = "부산 출발지",
            originLat = 35.1,
            originLng = 129.1,
            routeJson = """{"path":[3,4],"summary":"participant"}""",
            notificationEnabled = true,
            notificationLeadMinutes = 60,
            notificationIntervalMinutes = 20,
            scheduleFingerprint = ScheduleTravelPlanFingerprint.calculate(schedule),
        )

    private fun existingJob(scheduleId: Long, memberId: Long): SchedulePushJob =
        SchedulePushJob.create(
            memberId = memberId,
            scheduleId = scheduleId,
            scheduleAt = now.plusSeconds(7_200),
            departureAt = now.plusSeconds(4_800),
            monitorStartAt = now.plusSeconds(1_200),
            intervalMinutes = 20,
        )

    private fun ScheduleTravelPlan.toExpectedDto(schedule: Schedule): ScheduleTravelPlanDto =
        ScheduleTravelPlanDto(
            id = id,
            scheduleId = scheduleId,
            memberId = memberId,
            status = ScheduleTravelPlanStatus.READY,
            travelMinutes = travelMinutes,
            departAt = departAt?.toString(),
            travelMode = travelMode,
            origin = com.noLate.schedule.domain.SchedulePlaceDto(
                name = originName,
                address = originAddress,
                lat = originLat,
                lng = originLng,
            ),
            destination = schedule.toDto(objectMapper).destination,
            route = objectMapper.readTree(requireNotNull(routeJson)),
            notificationEnabled = notificationEnabled,
            notificationLeadMinutes = notificationLeadMinutes,
            notificationIntervalMinutes = notificationIntervalMinutes,
        )
}

@TestConfiguration
class SchedulePushJobBackfillTestConfig {
    @Bean
    fun schedulePushJobBackfillClock(): Clock =
        Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneOffset.UTC)

    @Bean
    fun schedulePushJobBackfillObjectMapper(): ObjectMapper = jacksonObjectMapper()
}
