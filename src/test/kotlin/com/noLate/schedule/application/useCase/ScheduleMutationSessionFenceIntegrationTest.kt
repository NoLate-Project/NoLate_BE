package com.noLate.schedule.application.useCase

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.member.domain.member.LoginType
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.schedule.domain.ScheduleCategory
import com.noLate.schedule.domain.ScheduleCategoryDto
import com.noLate.schedule.domain.ScheduleDto
import com.noLate.schedule.domain.SchedulePlaceDto
import com.noLate.schedule.domain.ScheduleTravelMode
import com.noLate.schedule.domain.ScheduleTravelPlanUpsertCommand
import com.noLate.schedule.infrastructure.ScheduleCategoryRepository
import com.noLate.schedule.infrastructure.SchedulePushJobRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleTravelPlanRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:schedule-mutation-session-fence;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "schedule.push.enabled=false",
        "notification.push-outbox.enabled=false",
    ],
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ScheduleMutationSessionFenceIntegrationTest @Autowired constructor(
    private val scheduleUseCase: ScheduleUseCase,
    private val travelPlanUseCase: ScheduleTravelPlanUseCase,
    private val memberRepository: MemberRepository,
    private val categoryRepository: ScheduleCategoryRepository,
    private val scheduleRepository: ScheduleRepository,
    private val travelPlanRepository: ScheduleTravelPlanRepository,
    private val pushJobRepository: SchedulePushJobRepository,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    @Test
    fun `authenticated update and delete resume after generation advance and mutate nothing`() {
        val fixture = fixture("schedule-edit", sessionGeneration = 1L)
        val beforeJob = pushJobRepository.findAll().single { it.scheduleId == fixture.scheduleId }
        val beforeGeneration = beforeJob.notificationGeneration
        val updateRequest = fixture.schedule.copy(title = "stale update")

        val updateFailure = invokeAfterGenerationAdvance(fixture.memberId) {
            scheduleUseCase.updateSchedule(
                memberId = fixture.memberId,
                scheduleId = fixture.scheduleId,
                scheduleDto = updateRequest,
                presentedSessionGeneration = 1L,
            )
        }
        val deleteFailure = runCatching {
            scheduleUseCase.deleteSchedule(
                memberId = fixture.memberId,
                scheduleId = fixture.scheduleId,
                presentedSessionGeneration = 1L,
            )
        }.exceptionOrNull()

        assertInvalidToken(updateFailure)
        assertInvalidToken(deleteFailure)
        val persisted = scheduleRepository.findById(fixture.scheduleId).orElseThrow()
        assertEquals("schedule-edit", persisted.title)
        assertFalse(persisted.deleted)
        val afterJob = pushJobRepository.findAll().single { it.scheduleId == fixture.scheduleId }
        assertEquals(beforeGeneration, afterJob.notificationGeneration)
        assertEquals(2L, memberRepository.findById(fixture.memberId).orElseThrow().sessionGeneration)
    }

    @Test
    fun `authenticated travel plan write resumes after generation advance and mutates no plan or job`() {
        val fixture = fixture("travel-plan", sessionGeneration = 4L)
        val beforePlan = travelPlanRepository
            .findByScheduleIdAndMemberIdAndDeletedFalse(fixture.scheduleId, fixture.memberId)
        assertNotNull(beforePlan)
        val beforeJob = pushJobRepository.findAll().single { it.scheduleId == fixture.scheduleId }

        val failure = invokeAfterGenerationAdvance(fixture.memberId) {
            travelPlanUseCase.upsertMyTravelPlan(
                memberId = fixture.memberId,
                scheduleId = fixture.scheduleId,
                command = ScheduleTravelPlanUpsertCommand(
                    travelMinutes = 45,
                    travelMode = ScheduleTravelMode.CAR,
                    originName = "다른 출발지",
                    originLat = 37.3,
                    originLng = 127.3,
                    notificationEnabled = false,
                ),
                presentedSessionGeneration = 4L,
            )
        }

        assertInvalidToken(failure)
        val afterPlan = travelPlanRepository
            .findByScheduleIdAndMemberIdAndDeletedFalse(fixture.scheduleId, fixture.memberId)
        assertNotNull(afterPlan)
        assertEquals(beforePlan?.travelMinutes, afterPlan?.travelMinutes)
        assertEquals(beforePlan?.notificationEnabled, afterPlan?.notificationEnabled)
        val afterJob = pushJobRepository.findAll().single { it.scheduleId == fixture.scheduleId }
        assertEquals(beforeJob.notificationGeneration, afterJob.notificationGeneration)
        assertEquals(beforeJob.status, afterJob.status)
    }

    private fun invokeAfterGenerationAdvance(
        memberId: Long,
        mutation: () -> Unit,
    ): Throwable? {
        val authenticated = CountDownLatch(1)
        val resumeMutation = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit {
            authenticated.countDown()
            check(resumeMutation.await(10, TimeUnit.SECONDS))
            failure.set(runCatching(mutation).exceptionOrNull())
        }
        try {
            assertTrue(authenticated.await(10, TimeUnit.SECONDS))
            transactions.executeWithoutResult {
                val member = memberRepository.findByIdForUpdate(memberId)
                    ?: error("fixture member disappeared")
                member.sessionGeneration = Math.addExact(member.sessionGeneration, 1L)
            }
            resumeMutation.countDown()
            future.get(10, TimeUnit.SECONDS)
            return failure.get()
        } finally {
            resumeMutation.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun assertInvalidToken(failure: Throwable?) {
        assertTrue(failure is BusinessException, failure?.stackTraceToString())
        assertEquals(ErrorCode.INVALID_TOKEN, (failure as BusinessException).errorCode)
    }

    private fun fixture(
        title: String,
        sessionGeneration: Long,
    ): Fixture {
        val member = memberRepository.saveAndFlush(
            Member(
                name = title,
                password = "Password1!",
                email = "$title-session-fence@example.com",
                loginType = LoginType.COMMON,
                sessionGeneration = sessionGeneration,
            ),
        )
        val memberId = requireNotNull(member.id)
        val category = categoryRepository.saveAndFlush(
            ScheduleCategory(
                memberId = memberId,
                title = "업무",
                color = "#123456",
            ),
        )
        val schedule = scheduleUseCase.addSchedule(
            memberId = memberId,
            scheduleDto = scheduleDto(requireNotNull(category.id), title),
            presentedSessionGeneration = sessionGeneration,
        )
        return Fixture(
            memberId = memberId,
            scheduleId = requireNotNull(schedule.id),
            schedule = schedule,
        )
    }

    private fun scheduleDto(categoryId: Long, title: String): ScheduleDto =
        ScheduleDto(
            title = title,
            startAt = "2099-07-24T05:00:00Z",
            endAt = "2099-07-24T06:00:00Z",
            travelMinutes = 30,
            departAt = "2099-07-24T04:30:00Z",
            travelMode = ScheduleTravelMode.CAR,
            origin = SchedulePlaceDto(name = "출발지", lat = 37.1, lng = 127.1),
            destination = SchedulePlaceDto(name = "도착지", lat = 37.2, lng = 127.2),
            category = ScheduleCategoryDto(
                id = categoryId.toString(),
                title = "업무",
                color = "#123456",
            ),
            notificationEnabled = true,
            notificationLeadMinutes = 60,
            notificationIntervalMinutes = 20,
        )

    private data class Fixture(
        val memberId: Long,
        val scheduleId: Long,
        val schedule: ScheduleDto,
    )
}
