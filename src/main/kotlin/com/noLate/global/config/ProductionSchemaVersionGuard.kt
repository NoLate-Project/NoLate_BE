package com.noLate.global.config

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Production schema changes are deliberately manual. Hibernate validation proves that
 * mapped columns exist, while this marker proves that the reviewed pre/post checks and
 * ownership cleanup gate ran before any API or scheduled worker can start.
 */
@Component
@Profile("prod")
class ProductionSchemaVersionGuard(
    private val environment: Environment,
    private val jdbcTemplate: JdbcTemplate,
) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() {
        val ddlMode = environment.getProperty("spring.jpa.hibernate.ddl-auto")
            ?.trim()
            ?.lowercase()
        check(ddlMode == "validate") {
            "Production startup blocked: spring.jpa.hibernate.ddl-auto must be validate."
        }

        val sqlInitMode = environment.getProperty("spring.sql.init.mode")
            ?.trim()
            ?.lowercase()
        check(sqlInitMode == "never") {
            "Production startup blocked: spring.sql.init.mode must be never."
        }

        val markerCount = try {
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM application_schema_migrations
                WHERE version = ?
                """.trimIndent(),
                Int::class.java,
                REQUIRED_SCHEMA_VERSION,
            ) ?: 0
        } catch (_: DataAccessException) {
            // JDBC messages can contain SQL text or values. Do not attach the original cause
            // to the startup exception; the maintenance runbook has the sanitized checks.
            throw IllegalStateException(
                "Production startup blocked: the required schema marker is unavailable. " +
                    "Keep API and workers stopped and complete the migration runbook.",
            )
        }

        check(markerCount == 1) {
            "Production startup blocked: required schema marker $REQUIRED_SCHEMA_VERSION is absent. " +
                "Keep API and workers stopped and complete the migration runbook."
        }
    }

    companion object {
        const val REQUIRED_SCHEMA_VERSION = "2026-07-26-apple-token-lifecycle-v1"
    }
}
