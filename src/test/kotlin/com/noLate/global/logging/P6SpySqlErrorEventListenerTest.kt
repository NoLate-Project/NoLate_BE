package com.noLate.global.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.p6spy.engine.common.ConnectionInformation
import com.p6spy.engine.common.StatementInformation
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import java.sql.SQLException

class P6SpySqlErrorEventListenerTest {
    private val listener = P6SpySqlErrorEventListener()
    private val logger = LoggerFactory.getLogger(P6SpySqlErrorEventListener::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private val originalThreadName = Thread.currentThread().name
    private val originalLogLevel = logger.level

    @BeforeEach
    fun setUp() {
        logger.level = Level.INFO
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        Thread.currentThread().name = originalThreadName
        logger.detachAppender(appender)
        logger.level = originalLogLevel
        appender.stop()
    }

    @Test
    fun `일반 쿼리는 실제 바인딩 값과 함께 기록한다`() {
        val statementInformation = statementInformation("select * from member where id = 1")

        listener.onAfterAnyExecute(statementInformation, 1_000_000, null)

        assertEquals(1, appender.list.size)
        assertTrue(appender.list.single().formattedMessage.contains("select * from member where id = 1"))
    }

    @Test
    fun `스케줄러가 정상 실행한 쿼리는 기록하지 않는다`() {
        Thread.currentThread().name = "scheduling-1"

        listener.onAfterAnyExecute(statementInformation("select * from schedule_push_job"), 1_000_000, null)

        assertTrue(appender.list.isEmpty())
    }

    @Test
    fun `스케줄러 쿼리도 실행에 실패하면 기록한다`() {
        Thread.currentThread().name = "scheduling-1"

        listener.onAfterAnyExecute(
            statementInformation("update schedule_push_job set status = 'FAILED'"),
            1_000_000,
            SQLException("database error"),
        )

        assertEquals(1, appender.list.size)
        assertTrue(appender.list.single().formattedMessage.contains("status = 'FAILED'"))
    }

    private fun statementInformation(sqlWithValues: String): StatementInformation {
        val connectionInformation = mock<ConnectionInformation>()
        whenever(connectionInformation.connectionId).thenReturn(1)

        return mock<StatementInformation>().also {
            whenever(it.connectionInformation).thenReturn(connectionInformation)
            whenever(it.sqlWithValues).thenReturn(sqlWithValues)
        }
    }
}
