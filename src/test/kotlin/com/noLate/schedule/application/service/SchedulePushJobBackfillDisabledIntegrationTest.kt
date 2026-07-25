package com.noLate.schedule.application.service

import com.noLate.notification.support.ensureActivePushMember
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleShareContentMode
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlan
import com.noLate.schedule.domain.ScheduleTravelPlanFingerprint
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@DataJpaTest
@Import(
    SchedulePushJobBackfillCandidateReader::class,
    SchedulePushJobBackfillPairWriter::class,
    SchedulePushJobBackfill::class,
    SchedulePushJobService::class,
    ScheduleAccessPolicy::class,
    ScheduleSharingAvailabilityPolicy::class,
    SchedulePushJobBackfillTestConfig::class,
)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:schedule-push-backfill-disabled;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "schedule.sharing.enabled=false",
    ],
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SchedulePushJobBackfillDisabledIntegrationTest @Autowired constructor(
    private val scheduleRepository: ScheduleRepository,
    private val travelPlanRepository: ScheduleTravelPlanRepository,
    private val scheduleShareRepository: ScheduleShareRepository,
    private val pushJobRepository: SchedulePushJobRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val backfill: SchedulePushJobBackfill,
) {
    private val now = Instant.parse("2026-07-24T00:00:00Z")

    @Test
    fun `global off backfill keeps dormant participant rows but creates only the owner job`() {
        ensureActivePushMember(jdbcTemplate, 1L)
        ensureActivePushMember(jdbcTemplate, 2L)
        val startAt = now.plusSeconds(7_200)
        val schedule = Schedule(
            memberId = 1L,
            title = "owner route",
            startAt = startAt,
            endAt = startAt.plusSeconds(3_600),
        ).apply {
            updateCategorySnapshot("1", "owner", "#246BFE")
            updateRoute(
                travelMinutes = 30,
                departAt = startAt.minusSeconds(1_800),
                departedAt = null,
                travelMode = ScheduleTravelMode.CAR,
                locationName = "destination",
                originName = "owner origin",
                originAddress = null,
                originLat = 37.1,
                originLng = 127.1,
                destinationName = "destination",
                destinationAddress = null,
                destinationLat = 37.2,
                destinationLng = 127.2,
                routeJson = """{"summary":"owner"}""",
                notificationEnabled = true,
                notificationLeadMinutes = 60,
                notificationIntervalMinutes = 20,
            )
        }
        scheduleRepository.saveAndFlush(schedule)
        val scheduleId = requireNotNull(schedule.id)
        val plan = travelPlanRepository.saveAndFlush(
            ScheduleTravelPlan(
                scheduleId = scheduleId,
                memberId = 2L,
                travelMinutes = 40,
                departAt = startAt.minusSeconds(2_400),
                travelMode = ScheduleTravelMode.TRANSIT,
                originName = "participant origin",
                originLat = 35.1,
                originLng = 129.1,
                routeJson = """{"summary":"participant"}""",
                notificationEnabled = true,
                notificationLeadMinutes = 60,
                notificationIntervalMinutes = 20,
                scheduleFingerprint = ScheduleTravelPlanFingerprint.calculate(schedule),
            )
        )
        val share = scheduleShareRepository.saveAndFlush(
            ScheduleShare(
                scheduleId = scheduleId,
                ownerMemberId = 1L,
                targetMemberId = 2L,
                permission = ScheduleSharePermission.EDITOR,
                contentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
            )
        )

        backfill.registerMissingJobs()

        assertEquals(
            setOf(1L),
            pushJobRepository.findAllByScheduleId(scheduleId).map { it.memberId }.toSet(),
        )
        assertFalse(travelPlanRepository.findById(requireNotNull(plan.id)).orElseThrow().deleted)
        val persistedShare = scheduleShareRepository.findById(requireNotNull(share.id)).orElseThrow()
        assertEquals(ScheduleShareStatus.ACTIVE, persistedShare.status)
        assertFalse(persistedShare.deleted)
    }
}
