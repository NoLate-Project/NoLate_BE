package com.noLate.schedule.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.member.domain.member.Member
import com.noLate.member.infrastructure.MemberRepository
import com.noLate.notification.application.PushClient
import com.noLate.notification.application.PushSendResult
import com.noLate.notification.application.service.AppNotificationService
import com.noLate.notification.application.service.AppNotificationWriter
import com.noLate.notification.application.service.NotificationTokenService
import com.noLate.notification.application.service.PushSendHistoryService
import com.noLate.notification.application.useCase.NotificationUseCase
import com.noLate.notification.domain.PushSendStatus
import com.noLate.notification.infrastructure.PushSendHistoryRepository
import com.noLate.notification.infrastructure.AppNotificationRepository
import com.noLate.schedule.domain.Schedule
import com.noLate.schedule.domain.ScheduleShare
import com.noLate.schedule.domain.ScheduleSharePermission
import com.noLate.schedule.infrastructure.ScheduleDepartureStatusRepository
import com.noLate.schedule.infrastructure.ScheduleRepository
import com.noLate.schedule.infrastructure.ScheduleShareRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest
@Import(
    ScheduleDepartureStatusService::class,
    ScheduleDeparturePushNotificationListener::class,
    NotificationTokenService::class,
    PushSendHistoryService::class,
    AppNotificationService::class,
    AppNotificationWriter::class,
    NotificationUseCase::class,
    ScheduleDepartureConcurrencyTestConfig::class,
)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:schedule-departure;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ]
)
class ScheduleDepartureStatusConcurrencyIntegrationTest @Autowired constructor(
    private val service: ScheduleDepartureStatusService,
    private val memberRepository: MemberRepository,
    private val scheduleRepository: ScheduleRepository,
    private val shareRepository: ScheduleShareRepository,
    private val departureStatusRepository: ScheduleDepartureStatusRepository,
    private val pushSendHistoryRepository: PushSendHistoryRepository,
    private val appNotificationRepository: AppNotificationRepository,
    private val eventRecorder: ScheduleDepartureEventRecorder,
) {

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `concurrent departure requests persist one status and publish one push event`() {
        val fixture = createFixture()
        val callCount = 8
        val executor = Executors.newFixedThreadPool(callCount)
        val ready = CountDownLatch(callCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(callCount)
        val failures = ConcurrentLinkedQueue<Throwable>()

        repeat(callCount) {
            executor.submit {
                ready.countDown()
                start.await()
                try {
                    service.markDeparted(fixture.targetMemberId, fixture.scheduleId)
                } catch (error: Throwable) {
                    failures.add(error)
                } finally {
                    done.countDown()
                }
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS), "동시 출발 요청 작업자가 제한 시간 안에 준비되어야 한다.")
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS), "동시 출발 요청이 제한 시간 안에 완료되어야 한다.")
        executor.shutdownNow()

        assertTrue(failures.isEmpty(), failures.joinToString { it.stackTraceToString() })

        val statuses = departureStatusRepository.findAllByScheduleIdAndDeletedFalse(fixture.scheduleId)
        assertEquals(1, statuses.size)
        assertEquals(fixture.targetMemberId, statuses.single().memberId)
        assertNotNull(statuses.single().departedAt)

        // 상태 row를 만든 최초 요청만 true 전환을 얻으므로 푸시 이벤트도 정확히 하나여야 한다.
        assertEquals(1, eventRecorder.events.size)
        assertEquals(fixture.targetMemberId, eventRecorder.events.single().departedMemberId)

        // AFTER_COMMIT 리스너는 반드시 새 트랜잭션을 열어야 이력이 실제 DB에 남는다.
        // 토큰이 없는 테스트 오너도 NO_TOKEN 이력 하나가 생기면 발송 파이프라인을 통과한 것이다.
        val histories = pushSendHistoryRepository.findAllByScheduleIdOrderBySentAtDesc(
            fixture.scheduleId,
            org.springframework.data.domain.PageRequest.of(0, 10),
        )
        assertEquals(1, histories.size)
        assertEquals(fixture.ownerMemberId, histories.single().memberId)
        assertEquals("SCHEDULE_PARTICIPANT_DEPARTED", histories.single().payloadType)
        assertEquals(PushSendStatus.NO_TOKEN, histories.single().status)

        // 기기 토큰이 없어 push가 전달되지 않아도 사용자가 다음 실행 때 확인할 앱 알림은 남는다.
        val appNotifications = appNotificationRepository.findAllByMemberIdOrderByIdDesc(
            fixture.ownerMemberId
        )
        assertEquals(1, appNotifications.size)
        assertEquals("SCHEDULE_PARTICIPANT_DEPARTED", appNotifications.single().type)
        assertEquals(fixture.scheduleId, appNotifications.single().scheduleId)
    }

    private fun createFixture(): DepartureConcurrencyFixture {
        val suffix = System.nanoTime()
        val owner = memberRepository.saveAndFlush(
            Member(
                name = "Owner",
                password = "Password1!",
                email = "departure-owner-$suffix@example.com",
            )
        )
        val target = memberRepository.saveAndFlush(
            Member(
                name = "Target",
                password = "Password1!",
                email = "departure-target-$suffix@example.com",
            )
        )
        val schedule = scheduleRepository.saveAndFlush(
            Schedule(
                memberId = requireNotNull(owner.id),
                title = "동시 출발 일정",
                startAt = Instant.parse("2026-07-22T02:00:00Z"),
                endAt = Instant.parse("2026-07-22T03:00:00Z"),
            ).apply {
                updateCategorySnapshot("5", "공유", "#2979FF")
            }
        )
        shareRepository.saveAndFlush(
            ScheduleShare(
                scheduleId = requireNotNull(schedule.id),
                ownerMemberId = requireNotNull(owner.id),
                targetMemberId = requireNotNull(target.id),
                permission = ScheduleSharePermission.VIEWER,
            )
        )

        return DepartureConcurrencyFixture(
            scheduleId = requireNotNull(schedule.id),
            ownerMemberId = requireNotNull(owner.id),
            targetMemberId = requireNotNull(target.id),
        )
    }
}

@TestConfiguration
class ScheduleDepartureConcurrencyTestConfig {
    @Bean
    fun departureClock(): Clock = Clock.fixed(
        Instant.parse("2026-07-22T01:20:00Z"),
        ZoneOffset.UTC,
    )

    @Bean
    fun departureEventRecorder(): ScheduleDepartureEventRecorder = ScheduleDepartureEventRecorder()

    @Bean
    fun departureObjectMapper(): ObjectMapper = jacksonObjectMapper()

    @Bean
    fun departurePushClient(): PushClient = object : PushClient {
        override fun sendToToken(
            token: String,
            title: String,
            body: String,
            data: Map<String, String>,
        ): PushSendResult = error("토큰 없는 통합 테스트에서는 실제 공급자를 호출하면 안 된다.")
    }
}

class ScheduleDepartureEventRecorder {
    val events = ConcurrentLinkedQueue<ScheduleParticipantDepartedEvent>()

    @EventListener
    fun record(event: ScheduleParticipantDepartedEvent) {
        events.add(event)
    }
}

private data class DepartureConcurrencyFixture(
    val scheduleId: Long,
    val ownerMemberId: Long,
    val targetMemberId: Long,
)
