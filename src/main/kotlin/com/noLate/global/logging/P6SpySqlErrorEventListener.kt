package com.noLate.global.logging

import com.p6spy.engine.common.StatementInformation
import com.p6spy.engine.event.SimpleJdbcEventListener
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.concurrent.TimeUnit

/**
 * P6Spy를 통해 실행된 SQL을 기록한다.
 *
 * 애플리케이션 요청의 SQL은 실제 바인딩 값과 함께 기록한다.
 * 스케줄러가 정상적으로 실행한 SQL은 반복 로그를 피하기 위해 기록하지 않지만,
 * 실행에 실패한 SQL은 스레드와 관계없이 기록한다.
 */
class P6SpySqlErrorEventListener : SimpleJdbcEventListener() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun onAfterAnyExecute(
        statementInformation: StatementInformation,
        timeElapsedNanos: Long,
        sqlException: SQLException?,
    ) {
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(timeElapsedNanos)
        val safeSql = statementInformation.sqlWithValues.redactPushSensitiveValues()

        if (sqlException == null) {
            if (Thread.currentThread().name.startsWith(SCHEDULER_THREAD_PREFIX)) {
                return
            }

            log.info(
                "SQL executed. connectionId={}, elapsedMs={}, sql={}",
                statementInformation.connectionInformation.connectionId,
                elapsedMs,
                safeSql,
            )
            return
        }

        log.error(
            "SQL execution failed. connectionId={}, elapsedMs={}, sqlState={}, vendorCode={}, exceptionClass={}, sql={}",
            statementInformation.connectionInformation.connectionId,
            elapsedMs,
            sqlException.sqlState,
            sqlException.errorCode,
            sqlException.javaClass.simpleName,
            safeSql,
        )
    }

    private companion object {
        const val SCHEDULER_THREAD_PREFIX = "scheduling-"
    }
}

/**
 * P6Spy의 sqlWithValues는 push token/device id까지 문자열 literal로 치환한다. column 위치를
 * SQL 종류별로 추론하면 insert/update/select 변형에서 누락될 수 있으므로, provider endpoint
 * 또는 그 전송 이력을 다루는 문장은 모든 문자열 literal을 가린다. push_send_history에도
 * token row에서 복사한 raw deviceId가 있으므로 성공 SQL과 SQLException 모두 같은 경계다.
 */
private fun String.redactPushSensitiveValues(): String {
    if (PUSH_SENSITIVE_TABLES.none { contains(it, ignoreCase = true) }) return this
    return replace(SQL_STRING_LITERAL, "'[REDACTED]'")
}

private val PUSH_SENSITIVE_TABLES = setOf(
    "push_device_token",
    "push_send_history",
)

private val SQL_STRING_LITERAL = Regex("'(?:''|[^'])*'")
