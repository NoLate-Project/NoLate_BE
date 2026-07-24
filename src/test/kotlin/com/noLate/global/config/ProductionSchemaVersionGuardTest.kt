package com.noLate.global.config

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Files
import java.nio.file.Path

class ProductionSchemaVersionGuardTest {

    @Test
    fun `verified manual migration marker allows production startup`() {
        val jdbc = markerDatabase()
        jdbc.update(
            """
            INSERT INTO application_schema_migrations(version, description, applied_at)
            VALUES (?, 'verified test schema', CURRENT_TIMESTAMP)
            """.trimIndent(),
            ProductionSchemaVersionGuard.REQUIRED_SCHEMA_VERSION,
        )

        assertDoesNotThrow {
            guard(jdbc).afterSingletonsInstantiated()
        }
    }

    @Test
    fun `production refuses automatic Hibernate schema mutation`() {
        val error = assertThrows(IllegalStateException::class.java) {
            guard(markerDatabase(), ddlMode = "update").afterSingletonsInstantiated()
        }

        assertTrue(error.message!!.contains("ddl-auto must be validate"))
    }

    @Test
    fun `production refuses SQL bootstrap initialization`() {
        val error = assertThrows(IllegalStateException::class.java) {
            guard(markerDatabase(), sqlInitMode = "always").afterSingletonsInstantiated()
        }

        assertTrue(error.message!!.contains("sql.init.mode must be never"))
    }

    @Test
    fun `missing marker blocks startup`() {
        val error = assertThrows(IllegalStateException::class.java) {
            guard(markerDatabase()).afterSingletonsInstantiated()
        }

        assertTrue(error.message!!.contains(ProductionSchemaVersionGuard.REQUIRED_SCHEMA_VERSION))
    }

    @Test
    fun `database failure is reported without raw JDBC exception detail`() {
        val jdbc = JdbcTemplate(
            DriverManagerDataSource(
                "jdbc:h2:mem:schema-guard-no-table;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                "",
            ),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            guard(jdbc).afterSingletonsInstantiated()
        }

        assertNull(error.cause)
        assertFalse(error.message!!.contains("JdbcSQL", ignoreCase = true))
        assertFalse(error.message!!.contains("SELECT COUNT", ignoreCase = true))
    }

    @Test
    fun `manual migration canonicalizes non-object JSON and rejects missing account binding`() {
        val migration = Files.readString(
            Path.of("docs/notification/2026-07-24-push-delivery-linearization.sql"),
        ).replace(Regex("\\s+"), " ")

        assertTrue(
            migration.contains(
                "WHEN JSON_TYPE(data_json) = 'OBJECT' THEN JSON_SET(",
            ),
        )
        assertTrue(
            migration.contains(
                "OR JSON_EXTRACT(data_json, '$.logicalEventKey') IS NULL",
            ),
        )
        assertTrue(
            migration.contains(
                "OR JSON_EXTRACT(data_json, '$.recipientMemberId') IS NULL",
            ),
        )
    }

    @Test
    fun `manual migration requires an explicit full legacy push token drain before marker`() {
        val migration = Files.readString(
            Path.of("docs/notification/2026-07-24-push-delivery-linearization.sql"),
        )
        val runbook = Files.readString(
            Path.of("docs/notification/push-reliability-production-rollout.md"),
        )

        val precondition = migration.indexOf(
            "push linearization migration blocked: drain all legacy push tokens first",
        )
        val postcondition = migration.indexOf(
            "push linearization verification failed: legacy push token drain",
        )
        val firstSchemaMutation = migration.indexOf("ALTER TABLE app_notifications")
        val marker = migration.indexOf(
            "INSERT INTO application_schema_migrations(version, description, applied_at)",
        )

        assertTrue(precondition >= 0)
        assertTrue(firstSchemaMutation > precondition)
        assertTrue(postcondition > firstSchemaMutation)
        assertTrue(marker > postcondition)
        assertTrue(
            Regex(
                """IF\s+EXISTS\s*\(\s*SELECT\s+1\s+FROM\s+push_device_token\s+LIMIT\s+1\s*\)""",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(migration),
        )
        assertTrue(migration.contains("SELECT COUNT(*) AS remaining_legacy_push_tokens"))
        assertFalse(
            Regex("""(?im)^\s*DELETE\s+FROM\s+push_device_token\b""").containsMatchIn(migration),
            "Migration must fail closed instead of silently deleting legacy provider endpoints",
        )

        assertTrue(runbook.contains("SELECT COUNT(*) AS legacy_push_token_count"))
        assertTrue(
            Regex("""(?im)^\s*DELETE\s+FROM\s+push_device_token\b""").containsMatchIn(runbook),
            "Only the explicit, operator-approved runbook step may drain the token table",
        )
        assertTrue(runbook.contains("legacy_push_token_count_after_drain=0"))
        assertTrue(runbook.contains("재로그인한 뒤 push token을 재등록"))
    }

    @Test
    fun `manual migration refuses partial legacy job fingerprints and requires an explicit drain`() {
        val migration = Files.readString(
            Path.of("docs/notification/2026-07-24-push-delivery-linearization.sql"),
        )
        val runbook = Files.readString(
            Path.of("docs/notification/push-reliability-production-rollout.md"),
        )

        val precondition = migration.indexOf(
            "push linearization migration blocked: drain legacy schedule push jobs first",
        )
        val firstSchemaMutation = migration.indexOf("ALTER TABLE app_notifications")
        val postcondition = migration.indexOf(
            "push linearization verification failed: legacy schedule push job drain",
        )
        val marker = migration.indexOf(
            "INSERT INTO application_schema_migrations(version, description, applied_at)",
        )

        assertTrue(precondition >= 0)
        assertTrue(firstSchemaMutation > precondition)
        assertTrue(postcondition > firstSchemaMutation)
        assertTrue(marker > postcondition)
        assertTrue(
            Regex(
                """IF\s+EXISTS\s*\(\s*SELECT\s+1\s+FROM\s+schedule_push_job\s+LIMIT\s+1\s*\)""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).containsMatchIn(migration),
        )
        assertFalse(
            Regex("""(?im)^\s*DELETE\s+FROM\s+schedule_push_job\b""").containsMatchIn(migration),
            "Migration must not silently discard legacy job execution state",
        )
        assertFalse(
            Regex(
                """(?is)UPDATE\s+schedule_push_job\s+SET\s+notification_input_fingerprint""",
            ).containsMatchIn(migration),
            "A partial SQL hash must not be promoted to the full runtime semantic fingerprint",
        )
        assertTrue(migration.contains("remaining_legacy_schedule_push_jobs"))
        assertTrue(runbook.contains("legacy_schedule_push_job_count"))
        assertTrue(
            Regex("""(?im)^\s*DELETE\s+FROM\s+schedule_push_job\b""").containsMatchIn(runbook),
            "Only the explicit operator-approved runbook step may drain legacy jobs",
        )
        assertTrue(
            runbook.replace(Regex("\\s+"), " ").contains("participant travel-plan job"),
        )
    }

    @Test
    fun `manual migration removes and verifies raw opaque unique indexes by column composition`() {
        val migration = Files.readString(
            Path.of("docs/notification/2026-07-24-push-delivery-linearization.sql"),
        ).replace(Regex("\\s+"), " ")
        val runbook = Files.readString(
            Path.of("docs/notification/push-reliability-production-rollout.md"),
        )

        assertTrue(migration.contains("AND non_unique = 0"))
        assertTrue(migration.contains("column_name IN ('token', 'device_id')"))
        assertTrue(migration.contains("DROP INDEX `"))
        assertTrue(
            migration.contains(
                "arbitrary raw opaque unique index remains",
            ),
        )
        assertTrue(runbook.contains("remaining_raw_opaque_unique_indexes"))
    }

    private fun guard(
        jdbc: JdbcTemplate,
        ddlMode: String = "validate",
        sqlInitMode: String = "never",
    ): ProductionSchemaVersionGuard =
        ProductionSchemaVersionGuard(
            environment = MockEnvironment()
                .withProperty("spring.jpa.hibernate.ddl-auto", ddlMode)
                .withProperty("spring.sql.init.mode", sqlInitMode),
            jdbcTemplate = jdbc,
        )

    private fun markerDatabase(): JdbcTemplate =
        JdbcTemplate(
            DriverManagerDataSource(
                "jdbc:h2:mem:schema-guard-${databaseSequence++};MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                "",
            ),
        ).also { jdbc ->
            jdbc.execute(
                """
                CREATE TABLE application_schema_migrations (
                    version VARCHAR(100) NOT NULL PRIMARY KEY,
                    description VARCHAR(255) NOT NULL,
                    applied_at TIMESTAMP NOT NULL
                )
                """.trimIndent(),
            )
        }

    companion object {
        private var databaseSequence: Int = 0
    }
}
