package com.noLate.global.logging

import com.p6spy.engine.common.StatementInformation
import com.p6spy.engine.event.SimpleJdbcEventListener
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.concurrent.TimeUnit

/**
 * 정상 SQL은 기록하지 않고 JDBC 실행이 실패한 경우에만 쿼리와 예외를 남긴다.
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
        if (sqlException == null) return

        log.error(
            "SQL execution failed. connectionId={}, elapsedMs={}, sql={}",
            statementInformation.connectionInformation.connectionId,
            TimeUnit.NANOSECONDS.toMillis(timeElapsedNanos),
            statementInformation.sql,
            sqlException,
        )
    }
}
