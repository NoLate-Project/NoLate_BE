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
                    "account-deletion.common-mailbox-proof-policy-approved",
                    Boolean::class.java,
                    false,
                )
            ) {
                "Production startup blocked: current mailbox control is not explicitly approved " +
                    "as COMMON account ownership proof."
            }
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

        val markerCounts = try {
            jdbcTemplate.query(
                """
                SELECT version, COUNT(*) AS marker_count
                FROM application_schema_migrations
                WHERE version IN (?, ?, ?)
                GROUP BY version
                """.trimIndent(),
                { resultSet, _ ->
                    resultSet.getString("version") to resultSet.getInt("marker_count")
                },
                *REQUIRED_SCHEMA_VERSIONS.toTypedArray(),
            ).toMap()
        } catch (_: DataAccessException) {
            // JDBC messages can contain SQL text or values. Do not attach the original cause
            // to the startup exception; the maintenance runbook has the sanitized checks.
            throw IllegalStateException(
                "Production startup blocked: the required schema marker is unavailable. " +
                    "Keep API and workers stopped and complete the migration runbook.",
            )
        }

        // These migrations are independently deployable, so a later marker must never hide
        // that one of the earlier reviewed runbooks was skipped.
        val invalidMarkers = REQUIRED_SCHEMA_VERSIONS.filter { markerCounts[it] != 1 }
        check(invalidMarkers.isEmpty()) {
            "Production startup blocked: required schema markers " +
                "${invalidMarkers.joinToString()} are absent. " +
                "Keep API and workers stopped and complete the migration runbook."
        }
    }

    companion object {
        const val PUSH_RELIABILITY_SCHEMA_VERSION = "2026-07-24-push-reliability-v4"
        const val APPLE_TOKEN_LIFECYCLE_SCHEMA_VERSION =
            "2026-07-26-apple-token-lifecycle-v1"
        const val ACCOUNT_DELETION_SCHEMA_VERSION = "2026-07-26-account-deletion-v1"

        val REQUIRED_SCHEMA_VERSIONS = listOf(
            PUSH_RELIABILITY_SCHEMA_VERSION,
            APPLE_TOKEN_LIFECYCLE_SCHEMA_VERSION,
            ACCOUNT_DELETION_SCHEMA_VERSION,
        )
    }
}
