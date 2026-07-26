package com.noLate.global.config

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.net.URI

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

        val accountDeletionPublicOrigin =
            environment.getProperty("account-deletion.public-origin")?.trim().orEmpty()
        val publicOriginIsCanonicalHttps = runCatching {
            val uri = URI(accountDeletionPublicOrigin)
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null &&
                (uri.path.isNullOrBlank() || uri.path == "/")
        }.getOrDefault(false)
        check(publicOriginIsCanonicalHttps) {
            "Production startup blocked: account-deletion.public-origin must be an explicit " +
                "canonical HTTPS origin."
        }
        val accountDeletionSupportEmail =
            environment.getProperty("account-deletion.support-email")?.trim().orEmpty()
        check(
            accountDeletionSupportEmail.length in 3..254 &&
                Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
                    .matches(accountDeletionSupportEmail)
        ) {
            "Production startup blocked: account-deletion.support-email must be explicit."
        }
        val accountDeletionEnabled =
            environment.getProperty("account-deletion.enabled", Boolean::class.java, false)
        if (accountDeletionEnabled) {
            check(
                environment.getProperty(
                    "account-deletion.verification.email.enabled",
                    Boolean::class.java,
                    false,
                )
            ) {
                "Production startup blocked: the account-deletion email verifier must be enabled."
            }
            val deletionEmailFrom =
                environment.getProperty("account-deletion.verification.email.from")
                    ?.trim()
                    .orEmpty()
            check(
                deletionEmailFrom.length in 3..254 &&
                    Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(deletionEmailFrom)
            ) {
                "Production startup blocked: the account-deletion sender address is invalid."
            }
            check(!environment.getProperty("spring.mail.host").isNullOrBlank()) {
                "Production startup blocked: the account-deletion SMTP host is absent."
            }
            check(environment.getProperty("spring.mail.port", Int::class.java, 0) in 1..65_535) {
                "Production startup blocked: the account-deletion SMTP port is invalid."
            }
            check(
                !environment.getProperty("spring.mail.username").isNullOrBlank() &&
                    !environment.getProperty("spring.mail.password").isNullOrBlank()
            ) {
                "Production startup blocked: authenticated account-deletion SMTP credentials are absent."
            }
            check(
                environment.getProperty(
                    "spring.mail.properties.mail.smtp.auth",
                    Boolean::class.java,
                    false,
                ) &&
                    environment.getProperty(
                        "spring.mail.properties.mail.smtp.starttls.enable",
                        Boolean::class.java,
                        false,
                    ) &&
                    environment.getProperty(
                        "spring.mail.properties.mail.smtp.starttls.required",
                        Boolean::class.java,
                        false,
                    )
            ) {
                "Production startup blocked: authenticated required STARTTLS is mandatory for " +
                    "account-deletion email."
            }
            check(
                environment.getProperty("spring.mail.test-connection", Boolean::class.java, false)
            ) {
                "Production startup blocked: SMTP startup connection verification must be enabled."
            }
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
        const val REQUIRED_SCHEMA_VERSION = "2026-07-26-account-deletion-v1"
    }
}
