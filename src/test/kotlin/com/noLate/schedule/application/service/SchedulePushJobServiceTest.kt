package com.noLate.schedule.application.service

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.member.domain.member.Member
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.SchedulePlaceDto
import com.noLate.schedule.domain.SchedulePushJob
import com.noLate.schedule.domain.SchedulePushJobStatus
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.ConcurrencyFailureException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@ExtendWith(MockitoExtension::class)
class SchedulePushJobServiceTest {

    @Mock
    lateinit var repository: SchedulePushJobRepository

    @Mock
    lateinit var memberRepository: MemberRepository

    @Test
    fun `job이 없어도 actor와 owner member를 먼저 잠근 뒤 schedule job gap을 잠근다`() {
        whenever(repository.findMemberIdsByScheduleId(10L)).thenReturn(emptyList())
        whenever(repository.findAllByScheduleIdOrderByIdAsc(10L)).thenReturn(emptyList())
        whenever(memberRepository.findAllByIdsForUpdate(listOf(2L, 9L)))
            .thenReturn(listOf(Member(id = 9L, sessionGeneration = 3L)))
        val service = SchedulePushJobService(repository, memberRepository)

        service.lockForScheduleEdit(
            scheduleId = 10L,
            requiredMemberIds = listOf(9L, 2L),
            actorMemberId = 9L,
            presentedSessionGeneration = 3L,
        )

        inOrder(repository, memberRepository) {
            verify(repository).findMemberIdsByScheduleId(10L)
            verify(memberRepository).findAllByIdsForUpdate(listOf(2L, 9L))
            verify(repository).findAllByScheduleIdOrderByIdAsc(10L)
        }
    }

    @Test
    fun `편집 fence는 required member와 기존 job member를 합쳐 정렬 잠금한다`() {
        whenever(repository.findMemberIdsByScheduleId(10L))
            .thenReturn(listOf(7L, 2L, 7L))
        whenever(repository.findAllByScheduleIdOrderByIdAsc(10L)).thenReturn(emptyList())
        whenever(memberRepository.findAllByIdsForUpdate(listOf(2L, 5L, 7L, 9L)))
            .thenReturn(listOf(Member(id = 5L, sessionGeneration = 3L)))
        val service = SchedulePushJobService(repository, memberRepository)

        service.lockForScheduleEdit(
            scheduleId = 10L,
            requiredMemberIds = listOf(5L, 2L, 9L),
            actorMemberId = 5L,
            presentedSessionGeneration = 3L,
        )

        verify(memberRepository).findAllByIdsForUpdate(listOf(2L, 5L, 7L, 9L))
    }

    @Test
    fun `job gap 뒤 새 member job이 보이면 뒤늦은 member lock 없이 edit을 재시도시킨다`() {
        whenever(repository.findMemberIdsByScheduleId(10L))
            .thenReturn(listOf(2L), listOf(2L, 8L))
        whenever(repository.findAllByScheduleIdOrderByIdAsc(10L)).thenReturn(emptyList())
        whenever(memberRepository.findAllByIdsForUpdate(listOf(2L, 5L)))
            .thenReturn(listOf(Member(id = 5L, sessionGeneration = 3L)))
        val service = SchedulePushJobService(repository, memberRepository)

        assertThrows(ConcurrencyFailureException::class.java) {
            service.lockForScheduleEdit(
                scheduleId = 10L,
                requiredMemberIds = listOf(5L),
                actorMemberId = 5L,
                presentedSessionGeneration = 3L,
            )
        }

        verify(memberRepository).findAllByIdsForUpdate(listOf(2L, 5L))
        verify(memberRepository, org.mockito.kotlin.never())
            .findByIdForUpdate(8L)
    }

    @Test
    fun `stale actor generation is rejected after sorted member locks and before job gap lock`() {
        whenever(repository.findMemberIdsByScheduleId(10L)).thenReturn(listOf(2L))
        whenever(memberRepository.findAllByIdsForUpdate(listOf(2L, 9L)))
            .thenReturn(listOf(Member(id = 9L, sessionGeneration = 4L)))
        val service = SchedulePushJobService(repository, memberRepository)

        val failure = assertThrows(BusinessException::class.java) {
            service.lockForScheduleEdit(
                scheduleId = 10L,
                requiredMemberIds = listOf(9L),
                actorMemberId = 9L,
                presentedSessionGeneration = 3L,
            )
        }

        assertEquals(ErrorCode.INVALID_TOKEN, failure.errorCode)
        verify(repository, org.mockito.kotlin.never())
            .findAllByScheduleIdOrderByIdAsc(10L)
    }

    @Test
    fun `travel plan fence validates current actor generation before the member job lock`() {
        val member = Member(id = 9L, sessionGeneration = 4L)
        whenever(memberRepository.findByIdForUpdate(9L)).thenReturn(member)
        val service = SchedulePushJobService(repository, memberRepository)

        service.lockForTravelPlanEdit(
            scheduleId = 10L,
            memberId = 9L,
            presentedSessionGeneration = 4L,
        )

        inOrder(memberRepository, repository) {
            verify(memberRepository).findByIdForUpdate(9L)
            verify(repository).findByScheduleIdAndMemberIdForUpdate(10L, 9L)
        }
    }

    @Test
    fun `stale travel plan actor cannot reach the job or plan mutation boundary`() {
        whenever(memberRepository.findByIdForUpdate(9L))
            .thenReturn(Member(id = 9L, sessionGeneration = 4L))
        val service = SchedulePushJobService(repository, memberRepository)

        val failure = assertThrows(BusinessException::class.java) {
            service.lockForTravelPlanEdit(
                scheduleId = 10L,
                memberId = 9L,
                presentedSessionGeneration = 3L,
            )
        }

        assertEquals(ErrorCode.INVALID_TOKEN, failure.errorCode)
        verify(repository, org.mockito.kotlin.never())
            .findByScheduleIdAndMemberIdForUpdate(10L, 9L)
    }

    @Test
    fun `알림이 활성화된 저장 일정은 monitor start 시각으로 push job을 등록한다`() {
        whenever(repository.findByScheduleIdAndMemberIdForUpdate(10L, 1L)).thenReturn(null)
        whenever(repository.save(any<SchedulePushJob>())).thenAnswer { it.getArgument(0) }
        val service = SchedulePushJobService(repository)

        val result = service.registerFromScheduleDto(
            memberId = 1L,
            scheduleDto = ScheduleDto(
                id = 10L,
                title = "회의",
                startAt = "2026-06-12T03:00:00Z",
                travelMinutes = 30,
                departAt = "2026-06-12T02:30:00Z",
                travelMode = ScheduleTravelMode.CAR,
                origin = SchedulePlaceDto(lat = 37.1, lng = 127.1),
                destination = SchedulePlaceDto(lat = 37.2, lng = 127.2),
                category = ScheduleCategoryDto(id = "1", title = "업무", color = "#000000"),
                notificationEnabled = true,
                notificationLeadMinutes = 60,
                notificationIntervalMinutes = 20,
            ),
        )

        assertNotNull(result)
        assertEquals(Instant.parse("2026-06-12T01:30:00Z"), result?.monitorStartAt)
        assertEquals(result?.monitorStartAt, result?.nextCheckAt)
    }

    @Test
    fun `다시 알림 요청은 job을 5분 뒤로 재예약한다`() {
        val now = Instant.parse("2026-06-12T01:00:00Z")
        val job = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = Instant.parse("2026-06-12T02:00:00Z"),
            departureAt = now,
            monitorStartAt = now.minusSeconds(3600),
            intervalMinutes = 20,
        )
        whenever(repository.findByScheduleIdAndMemberIdForUpdate(10L, 1L)).thenReturn(job)

        SchedulePushJobService(
            schedulePushJobRepository = repository,
            departureSnoozeMinutes = 5,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        ).snoozeDepartureReminder(memberId = 1L, scheduleId = 10L)

        assertEquals(SchedulePushJobStatus.ACTIVE, job.status)
        assertEquals(now.plusSeconds(300), job.nextCheckAt)
        assertEquals(now.plusSeconds(300), job.snoozedUntil)
    }

    @Test
    fun `출발 완료로 취소된 job은 다시 알림 요청으로 재활성화하지 않는다`() {
        val now = Instant.parse("2026-06-12T01:00:00Z")
        val job = SchedulePushJob.create(
            memberId = 1L,
            scheduleId = 10L,
            scheduleAt = Instant.parse("2026-06-12T02:00:00Z"),
            departureAt = now,
            monitorStartAt = now.minusSeconds(3600),
            intervalMinutes = 20,
        )
        job.cancel()
        whenever(repository.findByScheduleIdAndMemberIdForUpdate(10L, 1L)).thenReturn(job)

        SchedulePushJobService(
            schedulePushJobRepository = repository,
            departureSnoozeMinutes = 5,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        ).snoozeDepartureReminder(memberId = 1L, scheduleId = 10L)

        assertEquals(SchedulePushJobStatus.CANCELED, job.status)
        assertEquals(job.monitorStartAt, job.nextCheckAt)
        assertEquals(null, job.snoozedUntil)
    }
}
