package com.noLate.schedule.application.service

import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant

class SchedulePushJobCoordinatorScopedClaimTest {
    private val repository = mock<SchedulePushJobRepository>()
    private val members = mock<MemberRepository>()
    private val entityManager = mock<EntityManager>()
    private val coordinator = SchedulePushJobCoordinator(
        repository = repository,
        memberRepository = members,
        entityManager = entityManager,
        departureAlarmSyncServiceProvider = mock(),
        liveActivityLifecyclePublisherProvider = mock(),
        scheduleEtaEvaluationHistoryRepository = mock(),
    )
    private val now = Instant.parse("2026-10-04T04:00:00Z")

    @Test
    fun `scoped claim locks member then exact job and detaches after flush`() {
        val member = activeMember()
        val job = job()
        whenever(repository.findByScheduleIdAndMemberIdForUpdate(363, 296)).thenReturn(job)

        assertSame(job, coordinator.claimDueJobForSchedule(296, 363, now, "scoped-worker"))

        inOrder(members, repository, entityManager) {
            verify(members).findByIdForUpdate(296)
            verify(repository).findByScheduleIdAndMemberIdForUpdate(363, 296)
            verify(repository).flush()
            verify(entityManager).detach(job)
        }
        verify(member).deleted
        verifyNoMoreInteractions(members, repository, entityManager)
        assertEquals(SchedulePushJobStatus.PROCESSING, job.status)
        assertEquals("scoped-worker", job.lockedBy)
        assertEquals(now, job.lockedAt)
    }

    @ParameterizedTest
    @CsvSource("0,363", "-1,363", "296,0", "296,-1")
    fun `invalid scoped IDs fail before database access`(memberId: Long, scheduleId: Long) {
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.claimDueJobForSchedule(memberId, scheduleId, now, "scoped-worker")
        }
        verifyNoInteractions(members, repository, entityManager)
    }

    @Test
    fun `missing member does not inspect any job`() {
        assertNull(coordinator.claimDueJobForSchedule(296, 363, now, "scoped-worker"))
        verify(members).findByIdForUpdate(296)
        verifyNoInteractions(repository, entityManager)
    }

    @Test
    fun `deleted member does not inspect any job`() {
        val member = mock<Member>()
        whenever(member.deleted).thenReturn(true)
        whenever(members.findByIdForUpdate(296)).thenReturn(member)

        assertNull(coordinator.claimDueJobForSchedule(296, 363, now, "scoped-worker"))
        verifyNoInteractions(repository, entityManager)
    }

    @Test
    fun `missing scoped job never falls back to global due lookup`() {
        activeMember()

        assertNull(coordinator.claimDueJobForSchedule(296, 363, now, "scoped-worker"))

        verify(repository).findByScheduleIdAndMemberIdForUpdate(363, 296)
        verifyNoMoreInteractions(repository)
        verifyNoInteractions(entityManager)
    }

    @ParameterizedTest
    @EnumSource(value = SchedulePushJobStatus::class, names = ["ACTIVE"], mode = EnumSource.Mode.EXCLUDE)
    fun `non active jobs cannot be claimed or recovered by scoped entry`(status: SchedulePushJobStatus) {
        activeMember()
        val job = job()
        ReflectionTestUtils.setField(job, "status", status)
        whenever(repository.findByScheduleIdAndMemberIdForUpdate(363, 296)).thenReturn(job)

        assertNull(coordinator.claimDueJobForSchedule(296, 363, now, "scoped-worker"))

        assertEquals(status, job.status)
        verify(repository).findByScheduleIdAndMemberIdForUpdate(363, 296)
        verifyNoMoreInteractions(repository)
        verifyNoInteractions(entityManager)
    }

    @Test
    fun `future job is not forced due`() {
        activeMember()
        val job = job(dueAt = now.plusSeconds(1))
        whenever(repository.findByScheduleIdAndMemberIdForUpdate(363, 296)).thenReturn(job)

        assertNull(coordinator.claimDueJobForSchedule(296, 363, now, "scoped-worker"))

        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
        verify(repository).findByScheduleIdAndMemberIdForUpdate(363, 296)
        verifyNoMoreInteractions(repository)
        verifyNoInteractions(entityManager)
    }

    @ParameterizedTest
    @CsvSource("297,363", "296,364")
    fun `repository identity mismatch is rejected defensively`(memberId: Long, scheduleId: Long) {
        activeMember()
        val job = job(memberId = memberId, scheduleId = scheduleId)
        whenever(repository.findByScheduleIdAndMemberIdForUpdate(363, 296)).thenReturn(job)

        assertNull(coordinator.claimDueJobForSchedule(296, 363, now, "scoped-worker"))

        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
        verify(repository).findByScheduleIdAndMemberIdForUpdate(363, 296)
        verifyNoMoreInteractions(repository)
        verifyNoInteractions(entityManager)
    }

    @Test
    fun `legacy test boundary without member repository cannot bypass member check`() {
        assertNull(
            SchedulePushJobCoordinator(repository)
                .claimDueJobForSchedule(296, 363, now, "scoped-worker"),
        )
        verifyNoInteractions(repository, entityManager)
    }

    private fun activeMember(): Member = mock<Member>().also {
        whenever(it.deleted).thenReturn(false)
        whenever(members.findByIdForUpdate(296)).thenReturn(it)
    }

    private fun job(
        memberId: Long = 296,
        scheduleId: Long = 363,
        dueAt: Instant = now,
    ): SchedulePushJob = SchedulePushJob.create(
        memberId = memberId,
        scheduleId = scheduleId,
        scheduleAt = now.plusSeconds(7_200),
        departureAt = now.plusSeconds(3_600),
        monitorStartAt = dueAt,
        intervalMinutes = 10,
    )
}
