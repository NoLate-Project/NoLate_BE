package com.noLate.auth.apple

import com.noLate.global.config.ProductionSchemaVersionGuard
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AppleTokenLifecycleMigrationContractTest {
    private val migrationPath =
        Path.of("docs/member/migrations/2026-07-26-apple-token-lifecycle.sql")

    @Test
    fun `migration preserves revocation rows after member cleanup and marks only after verification`() {
        val migration = Files.readString(migrationPath)
        val tableStart = migration.indexOf("CREATE TABLE IF NOT EXISTS apple_provider_credentials")
        val tableEnd = migration.indexOf(
            ") COMMENT='Encrypted Sign in with Apple credentials and durable revoke leases';",
            tableStart,
        )

        assertTrue(tableStart >= 0)
        assertTrue(tableEnd > tableStart)
        val tableDefinition = migration.substring(tableStart, tableEnd)
        assertFalse(tableDefinition.contains("FOREIGN KEY", ignoreCase = true))
        assertFalse(tableDefinition.contains("REFERENCES member", ignoreCase = true))

        val postcondition = migration.indexOf(
            "CALL assert_apple_token_lifecycle_postconditions();",
        )
        val marker = migration.indexOf(
            "'${ProductionSchemaVersionGuard.REQUIRED_SCHEMA_VERSION}'",
            postcondition,
        )
        assertTrue(postcondition > tableEnd)
        assertTrue(marker > postcondition)
        assertTrue(migration.contains("2026-07-24-push-reliability-v4"))
        assertTrue(migration.contains("indexed_columns = 'member_id,status,id'"))
        assertTrue(migration.contains("referenced_table_name IS NOT NULL"))
        assertTrue(migration.contains("credential retries must not have foreign keys"))
    }
}
