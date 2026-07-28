package com.noLate.global.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.Marker

/**
 * 요청 처리 SQL은 그대로 표시하되, 반복 실행되는 백그라운드 작업의 정상 SQL은 숨긴다.
 *
 * SQL logger만 차단하므로 스케줄러 자체의 경고와 오류 로그는 계속 확인할 수 있다.
 */
class BackgroundSchedulerSqlTurboFilter : TurboFilter() {
    override fun decide(
        marker: Marker?,
        logger: Logger?,
        level: Level?,
        format: String?,
        params: Array<out Any>?,
        throwable: Throwable?,
    ): FilterReply {
        if (logger == null || !logger.name.isHibernateSqlLogger()) {
            return FilterReply.NEUTRAL
        }
        return if (isBackgroundSchedulerSql()) {
            FilterReply.DENY
        } else {
            FilterReply.NEUTRAL
        }
    }
}

/**
 * 기동 스레드에서 실행되는 스케줄 복구처럼 스레드 이름만으로 구분할 수 없는 작업을 표시한다.
 *
 * 중첩 호출을 허용하고 반드시 원래 상태로 복구해 이후 일반 초기화 SQL이 누락되지 않게 한다.
 */
object BackgroundSchedulerSqlContext {
    private val nestingDepth = ThreadLocal<Int>()

    fun isActive(): Boolean = (nestingDepth.get() ?: 0) > 0

    fun <T> suppressSuccessfulSql(block: () -> T): T {
        val previousDepth = nestingDepth.get() ?: 0
        nestingDepth.set(previousDepth + 1)
        return try {
            block()
        } finally {
            if (previousDepth == 0) {
                nestingDepth.remove()
            } else {
                nestingDepth.set(previousDepth)
            }
        }
    }
}

internal fun isBackgroundSchedulerSql(): Boolean =
    BackgroundSchedulerSqlContext.isActive() ||
        isBackgroundSchedulerThread(Thread.currentThread().name)

internal fun isBackgroundSchedulerThread(threadName: String): Boolean =
    // main은 일반 초기화 SQL도 함께 실행하므로 통째로 제외하지 않는다.
    threadName.startsWith("scheduling-") ||
        threadName == "nolate-operational-snapshot" ||
        threadName == "apple-token-revocation"

private fun String.isHibernateSqlLogger(): Boolean =
    this == "org.hibernate.SQL" ||
        this.startsWith("org.hibernate.SQL.") ||
        this == "org.hibernate.orm.jdbc.bind" ||
        this.startsWith("org.hibernate.orm.jdbc.bind.")
