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

        if (sqlException == null) {
            if (Thread.currentThread().name.startsWith(SCHEDULER_THREAD_PREFIX)) {
                return
            }

            log.info(
                "SQL executed. connectionId={}, elapsedMs={}, sql={}",
                statementInformation.connectionInformation.connectionId,
                elapsedMs,
                statementInformation.sqlWithValues,
            )
            return
        }

        log.error(
            "SQL execution failed. connectionId={}, elapsedMs={}, sql={}",
            statementInformation.connectionInformation.connectionId,
            elapsedMs,
            statementInformation.sqlWithValues,
            sqlException,
        )
    }

    private companion object {
        const val SCHEDULER_THREAD_PREFIX = "scheduling-"
    }
}
