package com.noLate.schedule.application.cache

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Profile
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/**
 * Local/default upgrades may already have member rows when Hibernate creates the new table.
 * Production never uses this initializer; its offline migration/backfill marker is authoritative.
 */
@Service
@Profile("!prod")
class ScheduleCalendarCacheRevisionBootstrapService(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun backfillMissingRows(): Int {
        repeat(MAX_CONCURRENT_STARTUP_ATTEMPTS - 1) {
            try {
                return jdbcTemplate.update(BACKFILL_SQL)
            } catch (_: DuplicateKeyException) {
                // 다른 non-prod instance가 같은 누락 row를 먼저 넣었다. 각 statement는
                // autocommit 경계이므로 새 snapshot으로 portable INSERT-SELECT를 재시도한다.
            }
        }
        return jdbcTemplate.update(BACKFILL_SQL)
    }

    private companion object {
        const val MAX_CONCURRENT_STARTUP_ATTEMPTS = 3
        val BACKFILL_SQL = """
            INSERT INTO schedule_calendar_cache_revisions(member_id, revision)
            SELECT member_row.id, 0
            FROM `member` member_row
            WHERE NOT EXISTS (
                SELECT 1
                FROM schedule_calendar_cache_revisions cache_revision
                WHERE cache_revision.member_id = member_row.id
            )
        """.trimIndent()
    }
}

@Component
@Profile("!prod")
class ScheduleCalendarCacheRevisionBootstrapInitializer(
    private val bootstrapService: ScheduleCalendarCacheRevisionBootstrapService,
) : SmartInitializingSingleton {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun afterSingletonsInstantiated() {
        val inserted = bootstrapService.backfillMissingRows()
        log.info("Schedule calendar cache revision bootstrap completed. inserted={}", inserted)
    }
}
