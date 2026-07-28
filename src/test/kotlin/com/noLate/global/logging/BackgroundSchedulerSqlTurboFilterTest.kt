package com.noLate.global.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.spi.FilterReply
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class BackgroundSchedulerSqlTurboFilterTest {
    private val filter = BackgroundSchedulerSqlTurboFilter()
    private val originalThreadName = Thread.currentThread().name

    @AfterEach
    fun tearDown() {
        Thread.currentThread().name = originalThreadName
    }

    @Test
    fun `스케줄러의 Hibernate SQL과 바인딩 로그는 차단한다`() {
        val sqlLogger = LoggerFactory.getLogger("org.hibernate.SQL") as Logger
        val bindLogger = LoggerFactory.getLogger("org.hibernate.orm.jdbc.bind") as Logger

        listOf(
            "scheduling-1",
            "nolate-operational-snapshot",
            "apple-token-revocation",
        ).forEach { threadName ->
            Thread.currentThread().name = threadName

            assertEquals(FilterReply.DENY, decide(sqlLogger))
            assertEquals(FilterReply.DENY, decide(bindLogger))
        }
    }

    @Test
    fun `요청 SQL과 스케줄러의 일반 로그는 유지한다`() {
        val sqlLogger = LoggerFactory.getLogger("org.hibernate.SQL") as Logger
        val workerLogger = LoggerFactory.getLogger("com.noLate.ScheduleWorker") as Logger

        listOf("main", "nio-5522-exec-1").forEach { threadName ->
            Thread.currentThread().name = threadName
            assertEquals(FilterReply.NEUTRAL, decide(sqlLogger))
        }

        Thread.currentThread().name = "scheduling-1"
        assertEquals(FilterReply.NEUTRAL, decide(workerLogger))
    }

    @Test
    fun `main에서 명시한 스케줄 복구 구간만 SQL을 차단한다`() {
        val sqlLogger = LoggerFactory.getLogger("org.hibernate.SQL") as Logger
        Thread.currentThread().name = "main"

        assertEquals(FilterReply.NEUTRAL, decide(sqlLogger))
        BackgroundSchedulerSqlContext.suppressSuccessfulSql {
            assertEquals(FilterReply.DENY, decide(sqlLogger))
        }
        assertEquals(FilterReply.NEUTRAL, decide(sqlLogger))
    }

    private fun decide(logger: Logger): FilterReply =
        filter.decide(
            null,
            logger,
            Level.DEBUG,
            "test",
            null,
            null,
        )
}
