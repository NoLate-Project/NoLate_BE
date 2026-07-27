package com.noLate.schedule.infrastructure

import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.application.service.ScheduleAccessPolicy
import com.noLate.schedule.application.service.ScheduleSharingAvailabilityPolicy
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleCalendar
import com.noLate.schedule.domain.ScheduleCalendarMember
import com.noLate.schedule.domain.ScheduleCalendarMemberStatus
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.domain.ScheduleCategoryShare
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleShareInvitation
import com.noLate.schedule.domain.ScheduleShareInvitationStatus
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareResourceType
import com.noLate.schedule.domain.ScheduleShareStatus
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleType
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.TestPropertySource
import java.time.Instant

@DataJpaTest
@Import(
    ScheduleAccessPolicy::class,
    ScheduleSharingAvailabilityPolicy::class,
)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:schedule-sharing-disabled;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "schedule.sharing.enabled=false",
    ]
)
class ScheduleSharingDisabledPersistenceIntegrationTest @Autowired constructor(
    private val memberRepository: MemberRepository,
    private val scheduleRepository: ScheduleRepository,
    private val categoryRepository: ScheduleCategoryRepository,
    private val scheduleShareRepository: ScheduleShareRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository,
    private val invitationRepository: ScheduleShareInvitationRepository,
    private val calendarRepository: ScheduleCalendarRepository,
    private val calendarMemberRepository: ScheduleCalendarMemberRepository,
    private val accessPolicy: ScheduleAccessPolicy,
    private val availabilityPolicy: ScheduleSharingAvailabilityPolicy,
    private val entityManager: EntityManager,
) {

    @Test
    fun `global off native queries expose only owner UGC while every grant row remains dormant`() {
        val owner = memberRepository.saveAndFlush(
            Member(
                name = "Owner",
                password = "Password1!",
                email = "sharing-off-owner-${System.nanoTime()}@example.com",
            )
        )
        val target = memberRepository.saveAndFlush(
            Member(
                name = "Target",
                password = "Password1!",
                email = "sharing-off-target-${System.nanoTime()}@example.com",
            )
        )
        val ownerId = requireNotNull(owner.id)
        val targetId = requireNotNull(target.id)
        val category = categoryRepository.saveAndFlush(
            ScheduleCategory(
                memberId = ownerId,
                title = "dormant category",
                color = "#246BFE",
                sortOrder = 0,
            )
        )
        val calendar = calendarRepository.saveAndFlush(
            ScheduleCalendar(
                ownerMemberId = ownerId,
                title = "dormant calendar",
            )
        )
        val calendarId = requireNotNull(calendar.id)
        calendarMemberRepository.saveAllAndFlush(
            listOf(
                ScheduleCalendarMember(
                    calendarId = calendarId,
                    memberId = ownerId,
                    role = ScheduleCalendarRole.OWNER,
                ),
                ScheduleCalendarMember(
                    calendarId = calendarId,
                    memberId = targetId,
                    role = ScheduleCalendarRole.EDITOR,
                ),
            )
        )
        val startAt = Instant.parse("2026-08-10T01:00:00Z")
        val schedule = Schedule(
            memberId = ownerId,
            categoryId = category.id,
            calendarId = calendarId,
            scheduleType = ScheduleType.ROUTE,
            title = "private owner schedule",
            startAt = startAt,
            endAt = startAt.plusSeconds(3600),
        ).apply {
            updateCategorySnapshot(requireNotNull(category.id).toString(), category.title, category.color)
            updateRoute(
                travelMinutes = 30,
                departAt = startAt.minusSeconds(1800),
                departedAt = null,
                travelMode = ScheduleTravelMode.TRANSIT,
                locationName = "private destination",
                originName = "private origin",
                originAddress = null,
                originLat = 37.5,
                originLng = 127.0,
                destinationName = "destination",
                destinationAddress = null,
                destinationLat = 37.6,
                destinationLng = 127.1,
                routeJson = null,
                notificationEnabled = true,
                notificationLeadMinutes = 15,
                notificationIntervalMinutes = 5,
            )
        }
        val savedSchedule = scheduleRepository.saveAndFlush(schedule)
        val scheduleId = requireNotNull(savedSchedule.id)
        val directShare = scheduleShareRepository.saveAndFlush(
            ScheduleShare(
                scheduleId = scheduleId,
                ownerMemberId = ownerId,
                targetMemberId = targetId,
                permission = ScheduleSharePermission.EDITOR,
            )
        )
        val categoryShare = categoryShareRepository.saveAndFlush(
            ScheduleCategoryShare(
                categoryId = requireNotNull(category.id),
                ownerMemberId = ownerId,
                targetMemberId = targetId,
                permission = ScheduleSharePermission.EDITOR,
            )
        )
        val invitation = invitationRepository.saveAndFlush(
            ScheduleShareInvitation(
                resourceType = ScheduleShareResourceType.SCHEDULE,
                resourceId = scheduleId,
                ownerMemberId = ownerId,
                permission = ScheduleSharePermission.VIEWER,
                tokenHash = "a".repeat(64),
                expiresAt = Instant.parse("2027-08-10T01:00:00Z"),
            )
        )
        entityManager.flush()
        entityManager.clear()

        assertFalse(availabilityPolicy.enabled)
        assertFalse(accessPolicy.resolve(targetId, savedSchedule).canView)
        assertEquals(listOf(ownerId), accessPolicy.travelMemberIds(savedSchedule))

        assertTrue(scheduleRepository.findOwnedScheduleList(targetId).isEmpty())
        assertNull(scheduleRepository.findOwnedScheduleDetail(scheduleId, targetId))
        assertTrue(
            scheduleRepository.findOwnedOverlappingScheduleList(
                targetId,
                startAt.minusSeconds(60),
                startAt.plusSeconds(7200),
            ).isEmpty()
        )
        assertTrue(
            scheduleRepository.findOwnedUpcomingScheduleList(
                targetId,
                startAt.minusSeconds(60),
                PageRequest.of(0, 10),
            ).isEmpty()
        )
        assertTrue(
            scheduleRepository.searchOwnedScheduleList(
                memberId = targetId,
                keyword = "private",
                categoryId = null,
                rangeStart = null,
                rangeEnd = null,
            ).isEmpty()
        )
        assertTrue(
            scheduleRepository.findOwnedDepartureReadyScheduleList(
                targetId,
                startAt.minusSeconds(60),
                startAt.plusSeconds(7200),
            ).isEmpty()
        )
        assertEquals(listOf(scheduleId), scheduleRepository.findOwnedScheduleList(ownerId).map { it.id })
        assertEquals(scheduleId, scheduleRepository.findOwnedScheduleDetail(scheduleId, ownerId)?.id)
        assertEquals(
            listOf(scheduleId),
            scheduleRepository.findOwnedOverlappingScheduleList(
                ownerId,
                startAt.minusSeconds(60),
                startAt.plusSeconds(7200),
            ).map { it.id },
        )
        assertEquals(
            listOf(scheduleId),
            scheduleRepository.findOwnedUpcomingScheduleList(
                ownerId,
                startAt.minusSeconds(60),
                PageRequest.of(0, 10),
            ).map { it.id },
        )
        assertEquals(
            listOf(scheduleId),
            scheduleRepository.searchOwnedScheduleList(
                memberId = ownerId,
                keyword = "private",
                categoryId = null,
                rangeStart = null,
                rangeEnd = null,
            ).map { it.id },
        )
        assertEquals(
            listOf(scheduleId),
            scheduleRepository.findOwnedDepartureReadyScheduleList(
                ownerId,
                startAt.minusSeconds(60),
                startAt.plusSeconds(7200),
            ).map { it.id },
        )
        assertTrue(
            categoryRepository
                .findByMemberIdAndDeletedFalseOrderBySortOrderAscIdAsc(targetId)
                .isEmpty()
        )
        assertEquals(
            listOf(category.id),
            categoryRepository
                .findByMemberIdAndDeletedFalseOrderBySortOrderAscIdAsc(ownerId)
                .map { it.id },
        )

        val persistedDirect = scheduleShareRepository.findById(requireNotNull(directShare.id)).orElseThrow()
        val persistedCategory = categoryShareRepository.findById(requireNotNull(categoryShare.id)).orElseThrow()
        val persistedInvitation = invitationRepository.findById(requireNotNull(invitation.id)).orElseThrow()
        val persistedMembership = calendarMemberRepository.findByCalendarIdAndMemberId(calendarId, targetId)
        assertEquals(ScheduleShareStatus.ACTIVE, persistedDirect.status)
        assertFalse(persistedDirect.deleted)
        assertEquals(ScheduleShareStatus.ACTIVE, persistedCategory.status)
        assertFalse(persistedCategory.deleted)
        assertEquals(ScheduleShareInvitationStatus.PENDING, persistedInvitation.status)
        assertFalse(persistedInvitation.deleted)
        assertEquals(ScheduleCalendarMemberStatus.ACTIVE, persistedMembership?.status)
        assertFalse(requireNotNull(persistedMembership).deleted)
    }
}
