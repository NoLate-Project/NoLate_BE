package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.ScheduleCalendarMemberStatus
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.ScheduleShareContentMode
import com.noLate.schedule.domain.ScheduleType
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarRepository
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.time.Instant

@DataJpaTest
@Import(
    ScheduleCalendarService::class,
    ScheduleTravelAccessCleanupService::class,
    ScheduleAccessPolicy::class,
)
class ScheduleCalendarServiceIntegrationTest @Autowired constructor(
    private val service: ScheduleCalendarService,
    private val memberRepository: MemberRepository,
    private val calendarRepository: ScheduleCalendarRepository,
    private val calendarMemberRepository: ScheduleCalendarMemberRepository,
    private val scheduleRepository: ScheduleRepository,
    private val pushJobRepository: SchedulePushJobRepository,
) {

    @Test
    fun `creating calendar also creates exactly one owner membership`() {
        val owner = member("owner")

        val result = service.createCalendar(
            ownerMemberId = requireNotNull(owner.id),
            title = "가족",
            color = "#2F80FF",
            defaultContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
        )

        val memberships = calendarMemberRepository
            .findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(result.id)
        assertEquals(1, memberships.size)
        assertEquals(requireNotNull(owner.id), memberships.single().memberId)
        assertEquals(ScheduleCalendarRole.OWNER, memberships.single().role)
        assertEquals(ScheduleCalendarRole.OWNER, result.myRole)
    }

    @Test
    fun `removed member is reactivated in same row when shared again`() {
        val owner = member("owner")
        val target = member("target")
        val calendar = createCalendar(owner)

        val first = service.addMember(
            ownerMemberId = requireNotNull(owner.id),
            calendarId = calendar.id,
            targetEmail = target.email,
            targetAppId = null,
            role = ScheduleCalendarRole.VIEWER,
        )
        service.removeMember(
            ownerMemberId = requireNotNull(owner.id),
            calendarId = calendar.id,
            targetMemberId = requireNotNull(target.id),
        )
        val reactivated = service.addMember(
            ownerMemberId = requireNotNull(owner.id),
            calendarId = calendar.id,
            targetEmail = null,
            targetAppId = target.id,
            role = ScheduleCalendarRole.EDITOR,
        )

        assertEquals(first.id, reactivated.id)
        assertEquals(ScheduleCalendarRole.EDITOR, reactivated.role)
        val persisted = calendarMemberRepository.findByCalendarIdAndMemberId(calendar.id, requireNotNull(target.id))
        assertEquals(ScheduleCalendarMemberStatus.ACTIVE, persisted?.status)
        assertFalse(requireNotNull(persisted).deleted)
    }

    @Test
    fun `ownership transfer leaves exactly one active owner`() {
        val owner = member("owner")
        val target = member("target")
        val calendar = createCalendar(owner)
        service.addMember(
            ownerMemberId = requireNotNull(owner.id),
            calendarId = calendar.id,
            targetEmail = target.email,
            targetAppId = null,
            role = ScheduleCalendarRole.EDITOR,
        )

        service.transferOwnership(
            ownerMemberId = requireNotNull(owner.id),
            calendarId = calendar.id,
            targetMemberId = requireNotNull(target.id),
        )

        val storedCalendar = calendarRepository.findByIdAndStatusAndDeletedFalse(calendar.id)
        assertEquals(requireNotNull(target.id), storedCalendar?.ownerMemberId)
        val members = calendarMemberRepository
            .findAllByCalendarIdAndStatusAndDeletedFalseOrderByIdAsc(calendar.id)
        assertEquals(1, members.count { it.role == ScheduleCalendarRole.OWNER })
        assertEquals(ScheduleCalendarRole.EDITOR, members.single { it.memberId == owner.id }.role)
        assertEquals(ScheduleCalendarRole.OWNER, members.single { it.memberId == target.id }.role)
    }

    @Test
    fun `owner cannot leave before transferring ownership`() {
        val owner = member("owner")
        val calendar = createCalendar(owner)

        assertThrows(BusinessException::class.java) {
            service.leaveCalendar(requireNotNull(owner.id), calendar.id)
        }

        val membership = calendarMemberRepository.findByCalendarIdAndMemberId(calendar.id, requireNotNull(owner.id))
        assertTrue(membership?.status == ScheduleCalendarMemberStatus.ACTIVE)
    }

    @Test
    fun `archiving a calendar removes it from active member lists`() {
        val owner = member("archive-owner")
        val target = member("archive-target")
        val calendar = createCalendar(owner)
        service.addMember(
            ownerMemberId = requireNotNull(owner.id),
            calendarId = calendar.id,
            targetEmail = target.email,
            targetAppId = null,
            role = ScheduleCalendarRole.VIEWER,
        )
        service.archiveCalendar(requireNotNull(owner.id), calendar.id)

        assertTrue(service.getCalendars(requireNotNull(target.id)).isEmpty())
    }

    @Test
    fun `viewer can disable only their own route reminder preference`() {
        val owner = member("preference-owner")
        val target = member("preference-target")
        val calendar = createCalendar(owner)
        service.addMember(
            ownerMemberId = requireNotNull(owner.id),
            calendarId = calendar.id,
            targetEmail = target.email,
            targetAppId = null,
            role = ScheduleCalendarRole.VIEWER,
        )

        val updated = service.updateMyPreferences(
            memberId = requireNotNull(target.id),
            calendarId = calendar.id,
            routeReminderEnabled = false,
        )

        assertFalse(updated.routeReminderEnabled)
        assertEquals(ScheduleCalendarRole.VIEWER, updated.role)
    }

    @Test
    fun `owner role changes cannot overwrite another member route reminder preference`() {
        val owner = member("preference-role-owner")
        val target = member("preference-role-target")
        val calendar = createCalendar(owner)
        service.addMember(
            ownerMemberId = requireNotNull(owner.id),
            calendarId = calendar.id,
            targetEmail = target.email,
            targetAppId = null,
            role = ScheduleCalendarRole.VIEWER,
        )
        service.updateMyPreferences(
            memberId = requireNotNull(target.id),
            calendarId = calendar.id,
            routeReminderEnabled = false,
        )

        val updated = service.updateMember(
            ownerMemberId = requireNotNull(owner.id),
            calendarId = calendar.id,
            targetMemberId = requireNotNull(target.id),
            role = ScheduleCalendarRole.EDITOR,
        )

        assertEquals(ScheduleCalendarRole.EDITOR, updated.role)
        assertFalse(updated.routeReminderEnabled)
    }

    @Test
    fun `reducing calendar content to schedule only cancels member departure jobs`() {
        val owner = member("content-owner")
        val target = member("content-target")
        val calendar = calendarServiceWithTravel(owner)
        service.addMember(
            ownerMemberId = requireNotNull(owner.id),
            calendarId = calendar.id,
            targetEmail = target.email,
            targetAppId = null,
            role = ScheduleCalendarRole.VIEWER,
        )
        val schedule = scheduleRepository.saveAndFlush(
            Schedule(
                memberId = requireNotNull(owner.id),
                calendarId = calendar.id,
                scheduleType = ScheduleType.ROUTE,
                title = "함께 이동",
                startAt = Instant.parse("2026-07-25T01:00:00Z"),
                endAt = Instant.parse("2026-07-25T02:00:00Z"),
            ).apply {
                updateCategorySnapshot("1", "가족", "#2F80FF")
            }
        )
        val job = pushJobRepository.saveAndFlush(
            SchedulePushJob.create(
                memberId = requireNotNull(target.id),
                scheduleId = requireNotNull(schedule.id),
                scheduleAt = schedule.startAt,
                departureAt = schedule.startAt.minusSeconds(1_800),
                monitorStartAt = schedule.startAt.minusSeconds(5_400),
                intervalMinutes = 20,
            )
        )

        service.updateCalendar(
            ownerMemberId = requireNotNull(owner.id),
            calendarId = calendar.id,
            title = null,
            color = null,
            defaultContentMode = ScheduleShareContentMode.SCHEDULE_ONLY,
        )

        assertEquals(
            SchedulePushJobStatus.CANCELED,
            pushJobRepository.findById(requireNotNull(job.id)).orElseThrow().status,
        )
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `email and app id concurrent invitations converge to one active membership row`() {
        val owner = member("concurrent-owner")
        val target = member("concurrent-target")
        val calendar = createCalendar(owner)
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val calls: List<() -> Unit> = listOf(
            {
                service.addMember(
                    ownerMemberId = requireNotNull(owner.id),
                    calendarId = calendar.id,
                    targetEmail = target.email,
                    targetAppId = null,
                    role = ScheduleCalendarRole.VIEWER,
                )
            },
            {
                service.addMember(
                    ownerMemberId = requireNotNull(owner.id),
                    calendarId = calendar.id,
                    targetEmail = null,
                    targetAppId = target.id,
                    role = ScheduleCalendarRole.EDITOR,
                )
            },
        )

        calls.forEach { call ->
            executor.submit {
                ready.countDown()
                start.await()
                try {
                    call()
                } catch (error: Throwable) {
                    failures.add(error)
                } finally {
                    done.countDown()
                }
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertTrue(failures.isEmpty(), failures.joinToString { it.message.orEmpty() })
        val rows = calendarMemberRepository.findAll().filter {
            it.calendarId == calendar.id && it.memberId == target.id
        }
        assertEquals(1, rows.size)
        assertEquals(ScheduleCalendarMemberStatus.ACTIVE, rows.single().status)
    }

    private fun createCalendar(owner: Member) = service.createCalendar(
        ownerMemberId = requireNotNull(owner.id),
        title = "공유 캘린더",
        color = "#2F80FF",
        defaultContentMode = ScheduleShareContentMode.SCHEDULE_ONLY,
    )

    private fun calendarServiceWithTravel(owner: Member) = service.createCalendar(
        ownerMemberId = requireNotNull(owner.id),
        title = "이동 공유 캘린더",
        color = "#2F80FF",
        defaultContentMode = ScheduleShareContentMode.SCHEDULE_AND_TRAVEL,
    )

    private fun member(label: String): Member {
        val suffix = System.nanoTime()
        return memberRepository.saveAndFlush(
            Member(
                name = label,
                password = "Password1!",
                email = "$label-$suffix@example.com",
            )
        )
    }
}
