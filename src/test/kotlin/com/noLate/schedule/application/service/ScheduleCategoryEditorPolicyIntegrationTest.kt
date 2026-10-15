package com.noLate.schedule.application.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.ScheduleCalendar
import com.noLate.schedule.domain.ScheduleCalendarMember
import com.noLate.schedule.domain.ScheduleCalendarRole
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleCategoryShare
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.ScheduleImportProvider
import com.noLate.schedule.domain.ScheduleImportSource
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.infrastructure.ScheduleCalendarMemberRepository
import com.noLate.schedule.infrastructure.ScheduleCalendarRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.ScheduleCategoryShareRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import com.noLate.subscription.application.SubscriptionPolicyService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.mock.env.MockEnvironment

@DataJpaTest
class ScheduleCategoryEditorPolicyIntegrationTest @Autowired constructor(
    private val memberRepository: MemberRepository,
    private val scheduleRepository: ScheduleRepository,
    private val scheduleShareRepository: ScheduleShareRepository,
    private val categoryRepository: ScheduleCategoryRepository,
    private val categoryShareRepository: ScheduleCategoryShareRepository,
    private val calendarRepository: ScheduleCalendarRepository,
    private val calendarMemberRepository: ScheduleCalendarMemberRepository,
) {

    @Test
    fun `direct category editor can CRUD without calendar membership then loses author access on revoke`() {
        val owner = member("category-policy-owner")
        val editor = member("category-policy-editor")
        val ownerId = requireNotNull(owner.id)
        val editorId = requireNotNull(editor.id)
        val calendar = calendarRepository.saveAndFlush(
            ScheduleCalendar(ownerMemberId = ownerId, title = "팀 캘린더"),
        )
        val calendarId = requireNotNull(calendar.id)
        calendarMemberRepository.saveAndFlush(
            ScheduleCalendarMember(
                calendarId = calendarId,
                memberId = ownerId,
                role = ScheduleCalendarRole.OWNER,
            ),
        )
        val category = categoryRepository.saveAndFlush(
            ScheduleCategory(
                memberId = ownerId,
                calendarId = calendarId,
                title = "공유 프로젝트",
                color = "#246BFE",
            ),
        )
        val categoryId = requireNotNull(category.id)
        val grant = categoryShareRepository.saveAndFlush(
            ScheduleCategoryShare(
                categoryId = categoryId,
                ownerMemberId = ownerId,
                targetMemberId = editorId,
                permission = ScheduleSharePermission.EDITOR,
            ),
        )
        val service = scheduleService()
        val request = scheduleRequest(category, calendarId, "편집자가 만든 일정")

        val created = service.addSchedule(editorId, request)
        val createdId = requireNotNull(created.id)
        assertEquals(ownerId, created.ownerMemberId)
        assertEquals(ScheduleSharePermission.EDITOR, created.sharePermission)
        assertTrue(
            calendarMemberRepository.findByCalendarIdAndMemberIdAndStatusAndDeletedFalse(
                calendarId,
                editorId,
            ) == null,
        )
        assertTrue(service.getScheduleList(ownerId).any { it.id == createdId })

        val updated = service.updateSchedule(editorId, createdId, created.copy(title = "편집 완료"))
        assertEquals("편집 완료", updated.title)
        service.deleteSchedule(editorId, createdId)
        assertTrue(scheduleRepository.findById(createdId).orElseThrow().deleted)

        val retained = service.addSchedule(editorId, request.copy(title = "카테고리에 남을 일정"))
        val retainedId = requireNotNull(retained.id)
        grant.revoke()
        categoryShareRepository.saveAndFlush(grant)

        assertTrue(service.getScheduleList(editorId).none { it.id == retainedId })
        val detailFailure = assertThrows<BusinessException> {
            service.getScheduleDetail(editorId, retainedId)
        }
        assertEquals(ErrorCode.SCHEDULE_NOT_FOUND, detailFailure.errorCode)
        assertEquals(
            ErrorCode.FORBIDDEN,
            assertThrows<BusinessException> {
                service.updateSchedule(editorId, retainedId, retained.copy(title = "탈취 시도"))
            }.errorCode,
        )
        assertEquals(
            ErrorCode.FORBIDDEN,
            assertThrows<BusinessException> { service.deleteSchedule(editorId, retainedId) }.errorCode,
        )

        val ownerDetail = service.getScheduleDetail(ownerId, retainedId)
        assertEquals(ownerId, ownerDetail.ownerMemberId)
        assertFalse(scheduleRepository.findById(retainedId).orElseThrow().deleted)
        service.deleteSchedule(ownerId, retainedId)
        assertTrue(scheduleRepository.findById(retainedId).orElseThrow().deleted)
    }

    @Test
    fun `revoked category editor cannot replay an imported source or mutate its key`() {
        val owner = member("import-replay-owner")
        val editor = member("import-replay-editor")
        val ownerId = requireNotNull(owner.id)
        val editorId = requireNotNull(editor.id)
        val category = categoryRepository.saveAndFlush(
            ScheduleCategory(memberId = ownerId, title = "가져오기 공유", color = "#15AABF"),
        )
        val grant = categoryShareRepository.saveAndFlush(
            ScheduleCategoryShare(
                categoryId = requireNotNull(category.id),
                ownerMemberId = ownerId,
                targetMemberId = editorId,
                permission = ScheduleSharePermission.EDITOR,
            ),
        )
        val source = ScheduleImportSource(
            provider = ScheduleImportProvider.APPLE_DEVICE,
            calendarId = "eventkit-shared",
            eventId = "revoked-editor-event",
            occurrenceStartAt = "2099-10-01T01:00:00Z",
        )
        val service = scheduleService()
        val imported = service.importSchedule(
            editorId,
            scheduleRequest(category, null, "공유 분류 가져오기"),
            source,
        )
        val scheduleId = requireNotNull(imported.schedule.id)
        val keyBeforeRevoke = scheduleRepository.findById(scheduleId).orElseThrow().externalSourceKey

        grant.revoke()
        categoryShareRepository.saveAndFlush(grant)

        val failure = assertThrows<BusinessException> {
            service.importSchedule(
                editorId,
                scheduleRequest(category, null, "공유 분류 가져오기"),
                source,
            )
        }
        assertEquals(ErrorCode.SCHEDULE_NOT_FOUND, failure.errorCode)
        assertEquals(
            keyBeforeRevoke,
            scheduleRepository.findById(scheduleId).orElseThrow().externalSourceKey,
        )
        assertEquals(scheduleId, service.getScheduleDetail(ownerId, scheduleId).id)
    }

    @Test
    fun `deleted personal category keeps editor-authored schedules for owner and closes editor access`() {
        val owner = member("tombstone-personal-owner")
        val editor = member("tombstone-personal-editor")
        val ownerId = requireNotNull(owner.id)
        val editorId = requireNotNull(editor.id)
        val category = categoryRepository.saveAndFlush(
            ScheduleCategory(memberId = ownerId, title = "종료한 프로젝트", color = "#845EF7"),
        )
        val categoryId = requireNotNull(category.id)
        categoryShareRepository.saveAndFlush(
            ScheduleCategoryShare(
                categoryId = categoryId,
                ownerMemberId = ownerId,
                targetMemberId = editorId,
                permission = ScheduleSharePermission.EDITOR,
            ),
        )
        val service = scheduleService()
        val created = service.addSchedule(
            editorId,
            scheduleRequest(category, null, "삭제 후에도 남는 일정"),
        )
        val scheduleId = requireNotNull(created.id)

        category.softDelete()
        categoryRepository.saveAndFlush(category)

        assertTrue(service.getScheduleList(ownerId).any { it.id == scheduleId })
        assertEquals(scheduleId, service.getScheduleDetail(ownerId, scheduleId).id)
        assertTrue(
            service.getUpcomingScheduleList(ownerId, "2099-01-01T00:00:00Z", 10)
                .any { it.id == scheduleId },
        )
        assertTrue(
            service.searchScheduleList(ownerId, "삭제 후", null, null, null, 10)
                .any { it.id == scheduleId },
        )
        assertTrue(service.getScheduleList(editorId).none { it.id == scheduleId })
        assertEquals(
            ErrorCode.SCHEDULE_NOT_FOUND,
            assertThrows<BusinessException> { service.getScheduleDetail(editorId, scheduleId) }.errorCode,
        )
    }

    @Test
    fun `deleted calendar category retains calendar and direct schedule audience but closes direct category audience`() {
        val owner = member("tombstone-calendar-owner")
        val calendarEditor = member("tombstone-calendar-editor")
        val calendarViewer = member("tombstone-calendar-viewer")
        val categoryAuthor = member("tombstone-category-author")
        val categoryOnly = member("tombstone-category-only")
        val directRecipient = member("tombstone-direct-recipient")
        val ownerId = requireNotNull(owner.id)
        val calendarEditorId = requireNotNull(calendarEditor.id)
        val calendarViewerId = requireNotNull(calendarViewer.id)
        val categoryAuthorId = requireNotNull(categoryAuthor.id)
        val categoryOnlyId = requireNotNull(categoryOnly.id)
        val directRecipientId = requireNotNull(directRecipient.id)
        val calendar = calendarRepository.saveAndFlush(
            ScheduleCalendar(ownerMemberId = ownerId, title = "유지되는 캘린더"),
        )
        val calendarId = requireNotNull(calendar.id)
        listOf(
            ownerId to ScheduleCalendarRole.OWNER,
            calendarEditorId to ScheduleCalendarRole.EDITOR,
            calendarViewerId to ScheduleCalendarRole.VIEWER,
        ).forEach { (memberId, role) ->
            calendarMemberRepository.saveAndFlush(
                ScheduleCalendarMember(calendarId = calendarId, memberId = memberId, role = role),
            )
        }
        val category = categoryRepository.saveAndFlush(
            ScheduleCategory(
                memberId = ownerId,
                calendarId = calendarId,
                title = "종료된 팀 카테고리",
                color = "#20C997",
            ),
        )
        val categoryId = requireNotNull(category.id)
        listOf(categoryAuthorId to ScheduleSharePermission.EDITOR, categoryOnlyId to ScheduleSharePermission.VIEWER)
            .forEach { (memberId, permission) ->
                categoryShareRepository.saveAndFlush(
                    ScheduleCategoryShare(
                        categoryId = categoryId,
                        ownerMemberId = ownerId,
                        targetMemberId = memberId,
                        permission = permission,
                    ),
                )
            }
        val service = scheduleService()
        val created = service.addSchedule(
            categoryAuthorId,
            scheduleRequest(category, calendarId, "캘린더에 남는 일정"),
        )
        val scheduleId = requireNotNull(created.id)
        scheduleShareRepository.saveAndFlush(
            ScheduleShare(
                scheduleId = scheduleId,
                ownerMemberId = ownerId,
                targetMemberId = directRecipientId,
                permission = ScheduleSharePermission.VIEWER,
            ),
        )

        category.softDelete()
        categoryRepository.saveAndFlush(category)

        listOf(ownerId, calendarEditorId, calendarViewerId, directRecipientId).forEach { memberId ->
            assertTrue(service.getScheduleList(memberId).any { it.id == scheduleId })
            assertEquals(scheduleId, service.getScheduleDetail(memberId, scheduleId).id)
        }
        listOf(categoryAuthorId, categoryOnlyId).forEach { memberId ->
            assertTrue(service.getScheduleList(memberId).none { it.id == scheduleId })
            assertEquals(
                ErrorCode.SCHEDULE_NOT_FOUND,
                assertThrows<BusinessException> { service.getScheduleDetail(memberId, scheduleId) }.errorCode,
            )
        }
    }

    private fun scheduleService(): ScheduleService {
        val sharing = ScheduleSharingAvailabilityPolicy(
            MockEnvironment().withProperty("schedule.sharing.enabled", "true"),
        )
        val accessPolicy = ScheduleAccessPolicy(
            scheduleShareRepository = scheduleShareRepository,
            categoryShareRepository = categoryShareRepository,
            calendarRepository = calendarRepository,
            calendarMemberRepository = calendarMemberRepository,
            categoryRepository = categoryRepository,
            sharingAvailabilityPolicy = sharing,
        )
        return ScheduleService(
            scheduleRepository = scheduleRepository,
            objectMapper = jacksonObjectMapper().findAndRegisterModules(),

            categoryRepository = categoryRepository,
            categoryShareRepository = categoryShareRepository,
            scheduleShareRepository = scheduleShareRepository,
            scheduleAccessPolicy = accessPolicy,
            calendarRepository = calendarRepository,
            calendarMemberRepository = calendarMemberRepository,
            sharingAvailabilityPolicy = sharing,
        )
    }

    private fun scheduleRequest(
        category: ScheduleCategory,
        calendarId: Long?,
        title: String,
    ) = ScheduleDto(
        calendarId = calendarId,
        title = title,
        startAt = "2099-10-01T01:00:00Z",
        endAt = "2099-10-01T02:00:00Z",
        category = ScheduleCategoryDto(
            id = requireNotNull(category.id).toString(),
            title = category.title,
            color = category.color,
        ),
        notificationEnabled = false,
    )

    private fun member(label: String): Member = memberRepository.saveAndFlush(
        Member(
            name = label,
            password = "Password1!",
            email = "$label-${System.nanoTime()}@example.com",
        ),
    )
}
