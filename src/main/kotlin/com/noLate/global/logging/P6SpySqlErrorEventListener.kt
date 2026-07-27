package com.noLate.global.logging

import com.p6spy.engine.common.StatementInformation
import com.p6spy.engine.event.SimpleJdbcEventListener
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.concurrent.TimeUnit

/**
 * P6Spy를 통해 실행된 SQL의 안전한 실행 메타데이터만 기록한다.
 *
 * SQL 원문과 바인딩 값은 인증 정보, 알림 본문, 기기 식별자처럼 스키마가 늘어날 때마다
 * 새로 생기는 비밀을 포함할 수 있으므로 성공/실패 경로 모두 default-deny 한다.
 * 스케줄러가 정상적으로 실행한 SQL은 반복 로그를 피하기 위해 기록하지 않지만,
 * 실행에 실패한 SQL은 SQLState/vendor code와 안전한 operation/table 분류를 기록한다.
 */
class P6SpySqlErrorEventListener : SimpleJdbcEventListener() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun onAfterAnyExecute(
        statementInformation: StatementInformation,
        timeElapsedNanos: Long,
        sqlException: SQLException?,
    ) {
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(timeElapsedNanos)
        val sqlMetadata = statementInformation.sql.toSafeLogMetadata()

        if (sqlException == null) {
            if (Thread.currentThread().name.startsWith(SCHEDULER_THREAD_PREFIX)) {
                return
            }

            log.info(
                "SQL executed. connectionId={}, elapsedMs={}, operation={}, table={}",
                statementInformation.connectionInformation.connectionId,
                elapsedMs,
                sqlMetadata.operation,
                sqlMetadata.table,
            )
            return
        }

        log.error(
            "SQL execution failed. connectionId={}, elapsedMs={}, operation={}, table={}, sqlState={}, vendorCode={}, exceptionClass={}",
            statementInformation.connectionInformation.connectionId,
            elapsedMs,
            sqlMetadata.operation,
            sqlMetadata.table,
            sqlException.sqlState.toSafeSqlState(),
            sqlException.errorCode,
            sqlException.javaClass.simpleName,
        )
    }

    private companion object {
        const val SCHEDULER_THREAD_PREFIX = "scheduling-"
    }
}

private data class SafeSqlLogMetadata(
    val operation: String,
    val table: String,
)

/**
 * 반드시 parameterless SQL(`StatementInformation.sql`)만 검사한다. 결과에는 SQL 조각을
 * 포함하지 않고 미리 정한 operation과 검증된 identifier 하나만 남긴다. 파싱할 수 없는 SQL은
 * sqlWithValues로 fallback하지 않아 신규 테이블/문장도 비밀값이 노출되지 않는다.
 */
private fun String?.toSafeLogMetadata(): SafeSqlLogMetadata {
    val tokens = tokenizeSqlShape(this)
    val operation = tokens.firstOrNull()
        ?.uppercase()
        ?.takeIf(SAFE_SQL_OPERATIONS::contains)
        ?: UNKNOWN_OPERATION
    val tableToken = when (operation) {
        "INSERT", "REPLACE", "MERGE" -> tokens.tokenAfter("INTO")
        "UPDATE" -> tokens.getOrNull(1)
        "DELETE", "SELECT" -> tokens.tokenAfter("FROM")
        "TRUNCATE" -> tokens.tokenAfterOptionalTableKeyword(1)
        "CREATE", "ALTER", "DROP" -> tokens.tokenAfterOptionalTableKeyword(1)
        "CALL" -> tokens.getOrNull(1)
        else -> null
    }

    return SafeSqlLogMetadata(
        operation = operation,
        table = tableToken.toSafeTableLabel(),
    )
}

/**
 * 문자열 literal과 주석은 token 후보에서 제외한다. 이 함수의 결과도 로그에 직접 출력하지
 * 않으며, 구조 분류 뒤 [toSafeTableLabel] 검증을 통과한 identifier만 사용한다.
 */
private fun tokenizeSqlShape(sql: String?): List<String> {
    if (sql.isNullOrBlank()) return emptyList()

    val source = sql.take(MAX_SQL_INSPECTION_LENGTH)
    val tokens = mutableListOf<String>()
    var index = 0
    while (index < source.length && tokens.size < MAX_SQL_TOKENS) {
        when {
            source[index].isWhitespace() || source[index] in SQL_PUNCTUATION -> index += 1
            source[index] == '\'' -> index = skipQuotedLiteral(source, index, '\'')
            source[index] == '"' -> index = skipQuotedLiteral(source, index, '"')
            source[index] == '`' -> {
                val closingIndex = source.indexOf('`', startIndex = index + 1)
                if (closingIndex < 0) return tokens
                tokens += source.substring(index + 1, closingIndex)
                index = closingIndex + 1
            }
            source.startsWith("--", index) || source[index] == '#' -> {
                index = source.indexOf('\n', startIndex = index + 1)
                    .takeIf { it >= 0 }
                    ?: source.length
            }
            source.startsWith("/*", index) -> {
                val closingIndex = source.indexOf("*/", startIndex = index + 2)
                index = if (closingIndex >= 0) closingIndex + 2 else source.length
            }
            source[index].isSqlIdentifierCharacter() -> {
                val startIndex = index
                while (index < source.length && source[index].isSqlIdentifierCharacter()) {
                    index += 1
                }
                tokens += source.substring(startIndex, index)
            }
            else -> index += 1
        }
    }
    return tokens
}

private fun skipQuotedLiteral(
    source: String,
    openingIndex: Int,
    quote: Char,
): Int {
    var index = openingIndex + 1
    while (index < source.length) {
        if (source[index] == '\\') {
            index += 2
            continue
        }
        if (source[index] == quote) {
            if (index + 1 < source.length && source[index + 1] == quote) {
                index += 2
                continue
            }
            return index + 1
        }
        index += 1
    }
    return source.length
}

private fun Char.isSqlIdentifierCharacter(): Boolean =
    isLetterOrDigit() || this == '_' || this == '$' || this == '.'

private fun List<String>.tokenAfter(keyword: String): String? {
    val keywordIndex = indexOfFirst { it.equals(keyword, ignoreCase = true) }
    return if (keywordIndex >= 0) getOrNull(keywordIndex + 1) else null
}

private fun List<String>.tokenAfterOptionalTableKeyword(startIndex: Int): String? {
    val candidate = getOrNull(startIndex) ?: return null
    return if (candidate.equals("TABLE", ignoreCase = true)) {
        getOrNull(startIndex + 1)
    } else {
        candidate
    }
}

private fun String?.toSafeTableLabel(): String =
    this
        ?.takeIf { it.length <= MAX_TABLE_LABEL_LENGTH && SAFE_TABLE_IDENTIFIER.matches(it) }
        ?.lowercase()
        ?: UNKNOWN_TABLE

private fun String?.toSafeSqlState(): String =
    this
        ?.takeIf { it.length in 1..MAX_SQL_STATE_LENGTH && it.all(Char::isLetterOrDigit) }
        ?.uppercase()
        ?: UNKNOWN_SQL_STATE

private const val MAX_SQL_INSPECTION_LENGTH = 16_384
private const val MAX_SQL_TOKENS = 512
private const val MAX_TABLE_LABEL_LENGTH = 128
private const val MAX_SQL_STATE_LENGTH = 10
private const val UNKNOWN_OPERATION = "UNKNOWN"
private const val UNKNOWN_TABLE = "unknown"
private const val UNKNOWN_SQL_STATE = "unknown"

private val SQL_PUNCTUATION = setOf('(', ')', ',', ';')
private val SAFE_SQL_OPERATIONS = setOf(
    "SELECT",
    "INSERT",
    "UPDATE",
    "DELETE",
    "MERGE",
    "REPLACE",
    "CREATE",
    "ALTER",
    "DROP",
    "TRUNCATE",
    "CALL",
)
private val SAFE_TABLE_IDENTIFIER = Regex(
    "[A-Za-z_\\$][A-Za-z0-9_\\$]*(?:\\.[A-Za-z_\\$][A-Za-z0-9_\\$]*)?"
)
