package com.noLate.sharing.application

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.application.cache.ScheduleCalendarCacheInvalidationEvent
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleShareResourceType
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import com.noLate.sharing.domain.SharingMemberBlock
import com.noLate.sharing.domain.SharingReport
import com.noLate.sharing.domain.SharingReportReason
import com.noLate.sharing.domain.SharingReportStatus
import com.noLate.sharing.infrastructure.SharingMemberBlockRepository
import com.noLate.sharing.infrastructure.SharingReportRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SharingSafetyServiceTest {
    private val memberRepository = mock<MemberRepository>()
    private val blockRepository = mock<SharingMemberBlockRepository>()
    private val reportRepository = mock<SharingReportRepository>()
    private val scheduleShareRepository = mock<ScheduleShareRepository>()
    private val categoryShareRepository = mock<ScheduleCategoryShareRepository>()
    private val calendarRepository = mock<ScheduleCalendarRepository>()
    private val calendarMemberRepository = mock<ScheduleCalendarMemberRepository>()
    private val eventPublisher = mock<ApplicationEventPublisher>()
    private val service = SharingSafetyService(
        memberRepository = memberRepository,
        blockRepository = blockRepository,
        reportRepository = reportRepository,
        scheduleShareRepository = scheduleShareRepository,
        categoryShareRepository = categoryShareRepository,
        calendarRepository = calendarRepository,
        calendarMemberRepository = calendarMemberRepository,
        eventPublisher = eventPublisher,
        clock = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC),
        moderatorAccessPolicy = SharingModeratorAccessPolicy("1"),
    )

    @Test
    fun `block persists directional management state and invalidates both members`() {
        stubLockedMembers()
        whenever(blockRepository.findPairForUpdate(1L, 2L)).thenReturn(null)
        doAnswer { invocation ->
            invocation.getArgument<SharingMemberBlock>(0).apply { id = 9L }
        }.whenever(blockRepository).saveAndFlush(any())

        val blocked = service.blockMember(1L, 2L, presentedSessionGeneration = 7L)

        assertEquals(2L, blocked.memberId)
        assertEquals("Owner", blocked.name)
        val event = argumentCaptor<ScheduleCalendarCacheInvalidationEvent>()
        verify(eventPublisher).publishEvent(event.capture())
        assertEquals(setOf(1L, 2L), event.firstValue.memberIds)
        assertEquals("sharing-member-blocked", event.firstValue.reason)
    }

    @Test
    fun `report requires an active received share and stores normalized evidence once`() {
        stubLockedMembers()
        whenever(scheduleShareRepository.findByScheduleIdAndTargetMemberId(41L, 1L)).thenReturn(
            ScheduleShare(
                scheduleId = 41L,
                ownerMemberId = 2L,
                targetMemberId = 1L,
            )
        )
        whenever(
            reportRepository
                .findFirstByReporterMemberIdAndReportedMemberIdAndResourceTypeAndResourceIdAndStatusInAndDeletedFalseOrderByIdDesc(
                    eq(1L),
                    eq(2L),
                    eq(ScheduleShareResourceType.SCHEDULE),
                    eq(41L),
                    any(),
                )
        ).thenReturn(null)
        whenever(reportRepository.countByReporterMemberIdAndCreatedAtAfterAndDeletedFalse(eq(1L), any()))
            .thenReturn(0L)
        doAnswer { invocation ->
            invocation.getArgument<SharingReport>(0).apply { id = 12L }
        }.whenever(reportRepository).saveAndFlush(any())

        val report = service.reportShare(
            reporterMemberId = 1L,
            reportedMemberId = 2L,
            resourceType = ScheduleShareResourceType.SCHEDULE,
            resourceId = 41L,
            reason = SharingReportReason.HARASSMENT,
            details = "  반복적인 공유입니다.  ",
            presentedSessionGeneration = 7L,
        )

        assertEquals(12L, report.id)
        assertEquals(SharingReportStatus.SUBMITTED, report.status)
        val saved = argumentCaptor<SharingReport>()
        verify(reportRepository).saveAndFlush(saved.capture())
        assertEquals("반복적인 공유입니다.", saved.firstValue.details)
    }

    @Test
    fun `report without a current received relationship is rejected`() {
        stubLockedMembers()
        whenever(scheduleShareRepository.findByScheduleIdAndTargetMemberId(41L, 1L)).thenReturn(null)

        val error = assertThrows(BusinessException::class.java) {
            service.reportShare(
                reporterMemberId = 1L,
                reportedMemberId = 2L,
                resourceType = ScheduleShareResourceType.SCHEDULE,
                resourceId = 41L,
                reason = SharingReportReason.SPAM,
                details = null,
                presentedSessionGeneration = 7L,
            )
        }

        assertEquals(ErrorCode.SHARING_REPORT_NOT_ALLOWED, error.errorCode)
        verify(reportRepository, never()).saveAndFlush(any())
    }

    @Test
    fun `an existing open report makes repeated submission idempotent`() {
        stubLockedMembers()
        whenever(scheduleShareRepository.findByScheduleIdAndTargetMemberId(41L, 1L)).thenReturn(
            ScheduleShare(scheduleId = 41L, ownerMemberId = 2L, targetMemberId = 1L)
        )
        whenever(
            reportRepository
                .findFirstByReporterMemberIdAndReportedMemberIdAndResourceTypeAndResourceIdAndStatusInAndDeletedFalseOrderByIdDesc(
                    eq(1L),
                    eq(2L),
                    eq(ScheduleShareResourceType.SCHEDULE),
                    eq(41L),
                    any(),
                )
        ).thenReturn(
            SharingReport(
                id = 14L,
                reporterMemberId = 1L,
                reportedMemberId = 2L,
                resourceType = ScheduleShareResourceType.SCHEDULE,
                resourceId = 41L,
                reason = SharingReportReason.UNWANTED_SHARING,
            )
        )

        val report = service.reportShare(
            reporterMemberId = 1L,
            reportedMemberId = 2L,
            resourceType = ScheduleShareResourceType.SCHEDULE,
            resourceId = 41L,
            reason = SharingReportReason.SPAM,
            details = null,
            presentedSessionGeneration = 7L,
        )

        assertEquals(14L, report.id)
        assertEquals(SharingReportReason.UNWANTED_SHARING, report.reason)
        verify(reportRepository, never())
            .countByReporterMemberIdAndCreatedAtAfterAndDeletedFalse(eq(1L), any())
        verify(reportRepository, never()).saveAndFlush(any())
    }

    @Test
    fun `report intake is capped per reporter over a rolling day`() {
        stubLockedMembers()
        whenever(scheduleShareRepository.findByScheduleIdAndTargetMemberId(41L, 1L)).thenReturn(
            ScheduleShare(scheduleId = 41L, ownerMemberId = 2L, targetMemberId = 1L)
        )
        whenever(
            reportRepository
                .findFirstByReporterMemberIdAndReportedMemberIdAndResourceTypeAndResourceIdAndStatusInAndDeletedFalseOrderByIdDesc(
                    eq(1L),
                    eq(2L),
                    eq(ScheduleShareResourceType.SCHEDULE),
                    eq(41L),
                    any(),
                )
        ).thenReturn(null)
        whenever(reportRepository.countByReporterMemberIdAndCreatedAtAfterAndDeletedFalse(eq(1L), any()))
            .thenReturn(20L)

        val error = assertThrows(BusinessException::class.java) {
            service.reportShare(
                reporterMemberId = 1L,
                reportedMemberId = 2L,
                resourceType = ScheduleShareResourceType.SCHEDULE,
                resourceId = 41L,
                reason = SharingReportReason.SPAM,
                details = null,
                presentedSessionGeneration = 7L,
            )
        }

        assertEquals(ErrorCode.SHARING_REPORT_RATE_LIMITED, error.errorCode)
        verify(reportRepository, never()).saveAndFlush(any())
    }

    @Test
    fun `moderator resolves an open report with auditable actor and time`() {
        val report = SharingReport(
            id = 44L,
            reporterMemberId = 3L,
            reportedMemberId = 2L,
            resourceType = ScheduleShareResourceType.SCHEDULE,
            resourceId = 41L,
            reason = SharingReportReason.SPAM,
        )
        whenever(reportRepository.findByIdForUpdate(44L)).thenReturn(report)
        whenever(reportRepository.saveAndFlush(report)).thenReturn(report)
        whenever(memberRepository.findAllById(listOf(3L, 2L))).thenReturn(
            listOf(
                Member(id = 3L, email = "reporter@example.com"),
                Member(id = 2L, email = "reported@example.com"),
            )
        )

        val result = service.moderateReport(
            moderatorMemberId = 1L,
            reportId = 44L,
            status = SharingReportStatus.RESOLVED,
            resolutionNote = "  공유 해제 확인  ",
        )

        assertEquals(SharingReportStatus.RESOLVED, result.status)
        assertEquals(1L, result.moderatorMemberId)
        assertEquals("공유 해제 확인", result.resolutionNote)
        assertEquals("2026-08-01T00:00:00Z", result.resolvedAt)
    }

    private fun stubLockedMembers() {
        whenever(memberRepository.findAllByIdsForUpdate(listOf(1L, 2L))).thenReturn(
            listOf(
                Member(
                    id = 1L,
                    name = "Reporter",
                    password = "Password1!",
                    email = "reporter@example.com",
                    sessionGeneration = 7L,
                ),
                Member(
                    id = 2L,
                    name = "Owner",
                    password = "Password1!",
                    email = "owner@example.com",
                    sessionGeneration = 3L,
                ),
            )
        )
    }
}
