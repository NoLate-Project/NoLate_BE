package com.noLate.global.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.p6spy.engine.common.ConnectionInformation
import com.p6spy.engine.common.StatementInformation
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    @Test
    fun `push token 테이블 SQL은 문자열 값을 가려 원문 token을 기록하지 않는다`() {
        val rawToken = "secret-fcm-token"
        val statementInformation = statementInformation(
            "update push_device_token set token = '$rawToken' where device_id = 'device-1'"
        )

        listener.onAfterAnyExecute(statementInformation, 1_000_000, null)

        val message = appender.list.single().formattedMessage
        assertFalse(message.contains(rawToken))
        assertFalse(message.contains("device-1"))
        assertTrue(message.contains("[REDACTED]"))
    }

    @Test
    fun `push 전송 이력 성공 SQL은 raw device id와 payload를 기록하지 않는다`() {
        val rawDeviceId = "private-installation-id"
        val privatePayload = """{"scheduleId":"42","recipientMemberId":"7"}"""
        val statementInformation = statementInformation(
            """
            insert into push_send_history(device_id, data_json, status)
            values ('$rawDeviceId', '$privatePayload', 'SUCCESS')
            """.trimIndent(),
        )

        listener.onAfterAnyExecute(statementInformation, 1_000_000, null)

        val message = appender.list.single().formattedMessage
        assertFalse(message.contains(rawDeviceId))
        assertFalse(message.contains(privatePayload))
        assertFalse(message.contains("SUCCESS"))
        assertTrue(message.contains("[REDACTED]"))
    }

    @Test
    fun `SQLException message와 stack trace에 token이 있어도 구조화된 오류 정보만 기록한다`() {
        val rawToken = "secret-token-from-driver-error"
        val sqlException = SQLException(
            "constraint failed for token=$rawToken",
            "23000",
            1062,
        )

        listener.onAfterAnyExecute(
            statementInformation(
                "insert into push_device_token(token, device_id) values ('$rawToken', 'private-device')"
            ),
            2_000_000,
            sqlException,
        )

        val event = appender.list.single()
        val message = event.formattedMessage
        assertFalse(message.contains(rawToken))
        assertFalse(message.contains("constraint failed"))
        assertFalse(message.contains("private-device"))
        assertTrue(message.contains("sqlState=23000"))
        assertTrue(message.contains("vendorCode=1062"))
        assertTrue(message.contains("exceptionClass=SQLException"))
        assertEquals(null, event.throwableProxy)
    }

    @Test
    fun `push 전송 이력 SQLException은 raw device id와 driver 원문을 기록하지 않는다`() {
        val rawDeviceId = "private-history-device"
        val sqlException = SQLException(
            "failed history insert for device=$rawDeviceId",
            "40001",
            1213,
        )

        listener.onAfterAnyExecute(
            statementInformation(
                "insert into push_send_history(device_id, status) values ('$rawDeviceId', 'FAILED')",
            ),
            2_000_000,
            sqlException,
        )

        val event = appender.list.single()
        val message = event.formattedMessage
        assertFalse(message.contains(rawDeviceId))
        assertFalse(message.contains("failed history insert"))
        assertFalse(message.contains("'FAILED'"))
        assertTrue(message.contains("sqlState=40001"))
        assertTrue(message.contains("vendorCode=1213"))
        assertTrue(message.contains("exceptionClass=SQLException"))
        assertEquals(null, event.throwableProxy)
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
