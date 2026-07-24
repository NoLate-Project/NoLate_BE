package com.noLate.global.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
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
    fun `일반 쿼리는 바인딩 값 없이 operation과 table만 기록한다`() {
        val privateMemberId = "987654321"
        val statementInformation = statementInformation(
            sql = "select * from member where id = ?",
            sqlWithValues = "select * from member where id = $privateMemberId",
        )

        listener.onAfterAnyExecute(statementInformation, 1_000_000, null)

        val message = appender.list.single().formattedMessage
        assertTrue(message.contains("operation=SELECT"))
        assertTrue(message.contains("table=member"))
        assertFalse(message.contains(privateMemberId))
        assertFalse(message.contains("select *"))
        assertFalse(message.contains("sql="))
    }

    @Test
    fun `스케줄러가 정상 실행한 쿼리는 기록하지 않는다`() {
        Thread.currentThread().name = "scheduling-1"

        listener.onAfterAnyExecute(
            statementInformation("select * from schedule_push_job"),
            1_000_000,
            null,
        )

        assertTrue(appender.list.isEmpty())
    }

    @Test
    fun `스케줄러 쿼리도 실패하면 값 없이 안전한 오류 분류를 기록한다`() {
        Thread.currentThread().name = "scheduling-1"
        val privateStatus = "PRIVATE-FAILED-STATUS"

        listener.onAfterAnyExecute(
            statementInformation(
                sql = "update schedule_push_job set status = ? where id = ?",
                sqlWithValues = "update schedule_push_job set status = '$privateStatus' where id = 91",
            ),
            1_000_000,
            SQLException("database error for $privateStatus", "40001", 1213),
        )

        val event = appender.list.single()
        val message = event.formattedMessage
        assertTrue(message.contains("operation=UPDATE"))
        assertTrue(message.contains("table=schedule_push_job"))
        assertTrue(message.contains("sqlState=40001"))
        assertTrue(message.contains("vendorCode=1213"))
        assertFalse(message.contains(privateStatus))
        assertFalse(message.contains("database error"))
        assertEquals(null, event.throwableProxy)
    }

    @Test
    fun `모든 성공 SQL은 알려진 테이블과 신규 테이블 모두 값을 default deny 한다`() {
        val canaries = SecretCanaries()
        val cases = listOf(
            SqlCase(
                table = "app_notifications",
                sql = """
                    insert into app_notifications(title, body, data_json, logical_event_key)
                    values (?, ?, ?, ?)
                """.trimIndent(),
                sqlWithValues = """
                    insert into app_notifications(title, body, data_json, logical_event_key)
                    values ('${canaries.title}', '${canaries.body}', '${canaries.jsonPayload}', '${canaries.logicalEventKey}')
                """.trimIndent(),
            ),
            SqlCase(
                table = "refresh_token",
                sql = "insert into refresh_token(member_id, token) values (?, ?)",
                sqlWithValues = "insert into refresh_token(member_id, token) values (7, '${canaries.jwt}')",
            ),
            SqlCase(
                table = "push_device_token",
                sql = """
                    insert into push_device_token(member_id, token, device_id)
                    values (?, ?, ?)
                """.trimIndent(),
                sqlWithValues = """
                    insert into push_device_token(member_id, token, device_id)
                    values (7, '${canaries.rawToken}', '${canaries.rawDeviceId}')
                """.trimIndent(),
            ),
            SqlCase(
                table = "push_deliveries",
                sql = """
                    insert into push_deliveries(event_key, data_json, device_fingerprint)
                    values (?, ?, ?)
                """.trimIndent(),
                sqlWithValues = """
                    insert into push_deliveries(event_key, data_json, device_fingerprint)
                    values ('${canaries.logicalEventKey}', '${canaries.jsonPayload}', '${canaries.rawDeviceId}')
                """.trimIndent(),
            ),
            SqlCase(
                table = "push_send_history",
                sql = "insert into push_send_history(device_id, data_json, status) values (?, ?, ?)",
                sqlWithValues = """
                    insert into push_send_history(device_id, data_json, status)
                    values ('${canaries.rawDeviceId}', '${canaries.jsonPayload}', 'SUCCESS')
                """.trimIndent(),
            ),
            SqlCase(
                table = "member",
                sql = "insert into member(password, social_id) values (?, ?)",
                sqlWithValues = """
                    insert into member(password, social_id)
                    values ('${canaries.password}', '${canaries.socialIdentifier}')
                """.trimIndent(),
            ),
            // 등록되지 않은 미래 테이블도 별도 redact 목록 없이 같은 경계를 적용한다.
            SqlCase(
                table = "future_private_records",
                sql = "insert into future_private_records(secret_value) values (?)",
                sqlWithValues = """
                    insert into future_private_records(secret_value)
                    values ('${canaries.futureTableSecret}')
                """.trimIndent(),
            ),
        )

        cases.forEach { case ->
            appender.list.clear()

            listener.onAfterAnyExecute(
                statementInformation(case.sql, case.sqlWithValues),
                1_000_000,
                null,
            )

            val message = appender.list.single().formattedMessage
            assertTrue(message.contains("operation=INSERT"), message)
            assertTrue(message.contains("table=${case.table}"), message)
            assertContainsNoCanary(message, canaries)
            assertFalse(message.contains("values", ignoreCase = true), message)
        }
    }

    @Test
    fun `parameterless SQL이 없으면 sqlWithValues로 fallback하지 않는다`() {
        val privateValue = "PRIVATE-UNKNOWN-TABLE-VALUE"

        listener.onAfterAnyExecute(
            statementInformation(
                sql = "",
                sqlWithValues = "insert into unregistered_table(secret) values ('$privateValue')",
            ),
            1_000_000,
            null,
        )

        val message = appender.list.single().formattedMessage
        assertTrue(message.contains("operation=UNKNOWN"))
        assertTrue(message.contains("table=unknown"))
        assertFalse(message.contains("unregistered_table"))
        assertFalse(message.contains(privateValue))
    }

    @Test
    fun `SELECT 분류는 문자열 literal의 from 단어를 table로 기록하지 않는다`() {
        val privateLiteral = "from PRIVATE_FAKE_TABLE"
        val privatePassword = "PRIVATE-SELECT-PASSWORD"

        listener.onAfterAnyExecute(
            statementInformation(
                sql = "select '$privateLiteral' as description from member where password = ?",
                sqlWithValues = """
                    select '$privateLiteral' as description
                    from member
                    where password = '$privatePassword'
                """.trimIndent(),
            ),
            1_000_000,
            null,
        )

        val message = appender.list.single().formattedMessage
        assertTrue(message.contains("operation=SELECT"))
        assertTrue(message.contains("table=member"))
        assertFalse(message.contains(privateLiteral))
        assertFalse(message.contains(privatePassword))
    }

    @Test
    fun `constraint SQLException은 SQL 값과 driver message를 버리고 안전한 code만 기록한다`() {
        val canaries = SecretCanaries()
        val sqlException = SQLException(
            """
                duplicate ${canaries.title} ${canaries.body} ${canaries.jwt}
                ${canaries.rawToken} ${canaries.rawDeviceId} ${canaries.jsonPayload}
                ${canaries.password}
            """.trimIndent(),
            "23000",
            1062,
        )

        listener.onAfterAnyExecute(
            statementInformation(
                sql = """
                    insert into app_notifications(
                        title, body, data_json, logical_event_key, recipient_member_id
                    ) values (?, ?, ?, ?, ?)
                """.trimIndent(),
                sqlWithValues = """
                    insert into app_notifications(
                        title, body, data_json, logical_event_key, recipient_member_id
                    ) values (
                        '${canaries.title}', '${canaries.body}', '${canaries.jsonPayload}',
                        '${canaries.logicalEventKey}', 7
                    )
                """.trimIndent(),
            ),
            2_000_000,
            sqlException,
        )

        val event = appender.list.single()
        val message = event.formattedMessage
        assertTrue(message.contains("operation=INSERT"))
        assertTrue(message.contains("table=app_notifications"))
        assertTrue(message.contains("sqlState=23000"))
        assertTrue(message.contains("vendorCode=1062"))
        assertTrue(message.contains("exceptionClass=SQLException"))
        assertContainsNoCanary(message, canaries)
        assertFalse(message.contains("duplicate"))
        assertEquals(null, event.throwableProxy)
    }

    @Test
    fun `deadlock SQLException도 신규 테이블 값을 기록하지 않는다`() {
        val privatePassword = "PRIVATE-DEADLOCK-PASSWORD"
        val privateToken = "PRIVATE-DEADLOCK-JWT"
        val sqlException = SQLException(
            "deadlock for password=$privatePassword token=$privateToken",
            "40001",
            1213,
        )

        listener.onAfterAnyExecute(
            statementInformation(
                sql = "insert into future_auth_archive(password, refresh_value) values (?, ?)",
                sqlWithValues = """
                    insert into future_auth_archive(password, refresh_value)
                    values ('$privatePassword', '$privateToken')
                """.trimIndent(),
            ),
            2_000_000,
            sqlException,
        )

        val event = appender.list.single()
        val message = event.formattedMessage
        assertTrue(message.contains("operation=INSERT"))
        assertTrue(message.contains("table=future_auth_archive"))
        assertTrue(message.contains("sqlState=40001"))
        assertTrue(message.contains("vendorCode=1213"))
        assertFalse(message.contains(privatePassword))
        assertFalse(message.contains(privateToken))
        assertFalse(message.contains("deadlock for"))
        assertEquals(null, event.throwableProxy)
    }

    private fun statementInformation(
        sql: String,
        sqlWithValues: String = sql,
    ): StatementInformation {
        val connectionInformation = mock<ConnectionInformation>()
        whenever(connectionInformation.connectionId).thenReturn(1)

        return mock<StatementInformation>().also {
            whenever(it.connectionInformation).thenReturn(connectionInformation)
            whenever(it.sql).thenReturn(sql)
            whenever(it.sqlWithValues).thenReturn(sqlWithValues)
        }
    }

    private fun assertContainsNoCanary(
        message: String,
        canaries: SecretCanaries,
    ) {
        canaries.all.forEach { canary ->
            assertFalse(message.contains(canary), "log must not contain canary: $canary")
        }
    }

    private data class SqlCase(
        val table: String,
        val sql: String,
        val sqlWithValues: String,
    )

    private data class SecretCanaries(
        val title: String = "PRIVATE-TITLE-440E",
        val body: String = "PRIVATE-BODY-440E",
        val jsonPayload: String = """{"private":"PRIVATE-JSON-440E"}""",
        val logicalEventKey: String = "schedule:PRIVATE-LOGICAL-EVENT-440E",
        val jwt: String = "eyJ.PRIVATE-REFRESH-JWT-440E.signature",
        val rawToken: String = "PRIVATE-FCM-TOKEN-440E",
        val rawDeviceId: String = "PRIVATE-DEVICE-ID-440E",
        val password: String = "PRIVATE-PASSWORD-440E",
        val socialIdentifier: String = "PRIVATE-SOCIAL-ID-440E",
        val futureTableSecret: String = "PRIVATE-FUTURE-TABLE-440E",
    ) {
        val all = listOf(
            title,
            body,
            jsonPayload,
            logicalEventKey,
            jwt,
            rawToken,
            rawDeviceId,
            password,
            socialIdentifier,
            futureTableSecret,
        )
    }
}
