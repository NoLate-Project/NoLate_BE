package com.noLate.notification.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.notification.infrastructure.AppNotificationRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
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
    AppNotificationService::class,
    AppNotificationWriter::class,
    AppNotificationTestConfig::class,
)
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:app-notification;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
    ]
)
class AppNotificationServiceIntegrationTest @Autowired constructor(
    private val service: AppNotificationService,
    private val repository: AppNotificationRepository,
) {

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `같은 논리 알림이 동시에 기록돼도 사용자 알림은 한 건만 생성된다`() {
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
                    service.record(
                        memberId = 10L,
                        title = "참가자 출발",
                        body = "민수님이 출발했어요.",
                        data = mapOf(
                            "type" to "SCHEDULE_PARTICIPANT_DEPARTED",
                            "scheduleId" to "55",
                        ),
                        deduplicationKey = "departure:55:2",
                    )
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

        assertTrue(failures.isEmpty(), failures.joinToString { it.stackTraceToString() })
        assertEquals(1, repository.findAllByMemberIdOrderByIdDesc(10L).size)
    }

    @Test
    fun `커서 페이지는 최신순 항목과 전체 미확인 개수를 함께 반환한다`() {
        repeat(4) { index ->
            service.record(
                memberId = 20L,
                title = "알림 $index",
                body = "내용 $index",
                data = mapOf("type" to "SCHEDULE_DETAIL", "scheduleId" to "${index + 1}"),
            )
        }
        val latest = repository.findAllByMemberIdOrderByIdDesc(20L).first()
        service.markRead(memberId = 20L, notificationId = requireNotNull(latest.id))

        val firstPage = service.getInbox(memberId = 20L, cursorId = null, limit = 2, unreadOnly = false)

        assertEquals(2, firstPage.items.size)
        assertEquals(listOf("알림 3", "알림 2"), firstPage.items.map { it.title })
        assertEquals(3L, firstPage.unreadCount)
        assertEquals(firstPage.items.last().id, firstPage.nextCursor)

        val secondPage = service.getInbox(
            memberId = 20L,
            cursorId = firstPage.nextCursor,
            limit = 2,
            unreadOnly = false,
        )
        assertEquals(listOf("알림 1", "알림 0"), secondPage.items.map { it.title })
        assertNull(secondPage.nextCursor)
    }

    @Test
    fun `읽지 않은 알림 필터와 모두 읽음 처리가 같은 미확인 개수를 사용한다`() {
        repeat(3) { index ->
            service.record(
                memberId = 30L,
                title = "알림 $index",
                body = "내용",
                data = mapOf("type" to "GENERAL"),
            )
        }
        val first = repository.findAllByMemberIdOrderByIdDesc(30L).last()
        service.markRead(30L, requireNotNull(first.id))

        val unreadPage = service.getInbox(30L, null, 20, unreadOnly = true)
        assertEquals(2, unreadPage.items.size)
        assertTrue(unreadPage.items.all { !it.isRead })

        assertEquals(2, service.markAllRead(30L))
        assertEquals(0L, service.getUnreadCount(30L))
    }

    @Test
    fun `다른 회원의 알림 ID는 존재 여부를 노출하지 않고 읽음 처리할 수 없다`() {
        val notification = service.record(
            memberId = 40L,
            title = "비공개 알림",
            body = "내용",
            data = emptyMap(),
        )

        val error = assertThrows(BusinessException::class.java) {
            service.markRead(memberId = 41L, notificationId = requireNotNull(notification.id))
        }

        assertEquals(ErrorCode.NOTIFICATION_NOT_FOUND, error.errorCode)
        assertEquals(1L, service.getUnreadCount(40L))
    }
}

@TestConfiguration
class AppNotificationTestConfig {
    @Bean
    fun appNotificationClock(): Clock = Clock.fixed(
        Instant.parse("2026-07-22T01:00:00Z"),
        ZoneOffset.UTC,
    )

    @Bean
    fun appNotificationObjectMapper(): ObjectMapper = ObjectMapper()
}
