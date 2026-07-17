package com.noLate.global.logging

import com.p6spy.engine.common.StatementInformation
import com.p6spy.engine.event.SimpleJdbcEventListener
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.concurrent.TimeUnit

/**
 * P6Spy를 통해 실행된 SQL을 기록한다.
 *
 * 실제 바인딩 값은 비밀번호, 토큰 등 민감정보가 포함될 수 있어 의도적으로 기록하지 않는다.
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
            log.info(
                "SQL executed. connectionId={}, elapsedMs={}, sql={}",
                statementInformation.connectionInformation.connectionId,
                elapsedMs,
                statementInformation.sql,
            )
            return
        }

        log.error(
            "SQL execution failed. connectionId={}, elapsedMs={}, sql={}",
            statementInformation.connectionInformation.connectionId,
            elapsedMs,
            statementInformation.sql,
            sqlException,
        )
    }
}
