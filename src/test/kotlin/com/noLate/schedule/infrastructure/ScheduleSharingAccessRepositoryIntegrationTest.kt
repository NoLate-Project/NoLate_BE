package com.noLate.schedule.infrastructure

import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleCalendar
import com.noLate.schedule.domain.ScheduleCalendarMember
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.domain.ScheduleCategoryShare
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.domain.ScheduleShareStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.TestPropertySource
import java.time.Instant

@DataJpaTest
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:schedule-sharing-access;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "schedule.sharing.enabled=true",
    ]
)
class ScheduleSharingAccessRepositoryIntegrationTest @Autowired constructor(
    private val memberRepository: MemberRepository,
    private val scheduleRepository: ScheduleRepository,
    private val categoryRepository: ScheduleCategoryRepository,
    private val scheduleShareRepository: ScheduleShareRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository,
    private val calendarRepository: ScheduleCalendarRepository,
    private val calendarMemberRepository: ScheduleCalendarMemberRepository,
) {

    @Test
    fun `direct schedule share is visible in list and detail queries`() {
        val fixture = createFixture()
        scheduleShareRepository.saveAndFlush(
            ScheduleShare(
                scheduleId = fixture.directScheduleId,
                ownerMemberId = fixture.ownerId,
                targetMemberId = fixture.targetId,
                permission = ScheduleSharePermission.VIEWER,
                status = ScheduleShareStatus.ACTIVE,
            )
        )

        val visibleSchedules = scheduleRepository.findScheduleList(fixture.targetId)
        val detail = scheduleRepository.findScheduleDetail(fixture.directScheduleId, fixture.targetId)

        assertEquals(listOf(fixture.directScheduleId), visibleSchedules.map { it.id })
        assertEquals(fixture.directScheduleId, detail?.id)
    }

    @Test
    fun `category share exposes schedules that belong to the shared category`() {
        val fixture = createFixture()
        categoryShareRepository.saveAndFlush(
            ScheduleCategoryShare(
                categoryId = fixture.categoryId,
                ownerMemberId = fixture.ownerId,
                targetMemberId = fixture.targetId,
                permission = ScheduleSharePermission.VIEWER,
                status = ScheduleShareStatus.ACTIVE,
            )
        )

        val visibleSchedules = scheduleRepository.findScheduleList(fixture.targetId)
        val visibleCategories = categoryRepository.findVisibleCategories(fixture.targetId)

        assertEquals(listOf(fixture.directScheduleId, fixture.categoryScheduleId), visibleSchedules.map { it.id })
        assertEquals(listOf(fixture.categoryId), visibleCategories.map { it.id })
    }

    @Test
    fun `shared and owned searches apply their limit in the database query`() {
        val fixture = createFixture()
        categoryShareRepository.saveAndFlush(
            ScheduleCategoryShare(
                categoryId = fixture.categoryId,
                ownerMemberId = fixture.ownerId,
                targetMemberId = fixture.targetId,
                permission = ScheduleSharePermission.VIEWER,
                status = ScheduleShareStatus.ACTIVE,
            )
        )

        val sharedResult = scheduleRepository.searchScheduleList(
            memberId = fixture.targetId,
            keyword = null,
            categoryId = null,
            rangeStart = null,
            rangeEnd = null,
            pageable = PageRequest.of(0, 1),
        )
        val ownedResult = scheduleRepository.searchOwnedScheduleList(
            memberId = fixture.ownerId,
            keyword = null,
            categoryId = null,
            rangeStart = null,
            rangeEnd = null,
            pageable = PageRequest.of(0, 1),
        )

        assertEquals(listOf(fixture.directScheduleId), sharedResult.map { it.id })
        assertEquals(listOf(fixture.directScheduleId), ownedResult.map { it.id })
    }

    @Test
    fun `calendar scope is applied before limiting matches and does not grant visibility`() {
        val fixture = createFixture()
        val calendar = calendarRepository.saveAndFlush(
            ScheduleCalendar(ownerMemberId = fixture.ownerId, title = "공유 검토"),
        )
        val calendarId = requireNotNull(calendar.id)
        calendarMemberRepository.saveAndFlush(
            ScheduleCalendarMember(calendarId = calendarId, memberId = fixture.ownerId, role = ScheduleCalendarRole.OWNER),
        )
        val startAt = Instant.parse("2026-07-12T01:00:00Z")
        val scopedItem = scheduleRepository.saveAndFlush(
            Schedule(
                memberId = fixture.ownerId,
                calendarId = calendarId,
                title = "선택한 캘린더 일정",
                startAt = startAt,
                endAt = startAt.plusSeconds(3600),
            ),
        )
        val first = PageRequest.of(0, 1)

        assertEquals(listOf(scopedItem.id), scheduleRepository.searchScheduleList(
            fixture.ownerId, "일정", null, null, null, first, calendarId,
        ).map { it.id })
        assertEquals(listOf(scopedItem.id), scheduleRepository.searchOwnedScheduleList(
            fixture.ownerId, "일정", null, null, null, first, calendarId,
        ).map { it.id })
        assertEquals(listOf(fixture.directScheduleId), scheduleRepository.searchScheduleList(
            fixture.ownerId, "일정", null, null, null, first, 0L,
        ).map { it.id })
        assertEquals(emptyList<Schedule>(), scheduleRepository.searchScheduleList(
            fixture.targetId, "일정", null, null, null, first, calendarId,
        ))
    }

    private fun createFixture(): AccessFixture {
        val owner = memberRepository.saveAndFlush(
            Member(name = "Owner", password = "Password1!", email = "access-owner-${System.nanoTime()}@example.com")
        )
        val target = memberRepository.saveAndFlush(
            Member(name = "Target", password = "Password1!", email = "access-target-${System.nanoTime()}@example.com")
        )
        val category = categoryRepository.saveAndFlush(
            ScheduleCategory(
                memberId = requireNotNull(owner.id),
                title = "공유 카테고리",
                color = "#2196f3",
                sortOrder = 0,
            )
        )
        val directSchedule = scheduleRepository.saveAndFlush(
            schedule(
                ownerId = requireNotNull(owner.id),
                category = category,
                title = "직접 공유 일정",
                startAt = Instant.parse("2026-07-10T01:00:00Z"),
            )
        )
        val categorySchedule = scheduleRepository.saveAndFlush(
            schedule(
                ownerId = requireNotNull(owner.id),
                category = category,
                title = "카테고리 공유 일정",
                startAt = Instant.parse("2026-07-11T01:00:00Z"),
            )
        )

        return AccessFixture(
            ownerId = requireNotNull(owner.id),
            targetId = requireNotNull(target.id),
            categoryId = requireNotNull(category.id),
            directScheduleId = requireNotNull(directSchedule.id),
            categoryScheduleId = requireNotNull(categorySchedule.id),
        )
    }

    private fun schedule(
        ownerId: Long,
        category: ScheduleCategory,
        title: String,
        startAt: Instant,
    ): Schedule =
        Schedule(
            memberId = ownerId,
            categoryId = category.id,
            title = title,
            startAt = startAt,
            endAt = startAt.plusSeconds(3600),
        ).apply {
            updateCategorySnapshot(requireNotNull(category.id).toString(), category.title, category.color)
        }
}

private data class AccessFixture(
    val ownerId: Long,
    val targetId: Long,
    val categoryId: Long,
    val directScheduleId: Long,
    val categoryScheduleId: Long,
)
