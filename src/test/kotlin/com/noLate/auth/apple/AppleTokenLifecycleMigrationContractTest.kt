package com.noLate.auth.apple

import com.noLate.global.config.ProductionSchemaVersionGuard
import jakarta.persistence.Column
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.hibernate.annotations.Check
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
            "'${ProductionSchemaVersionGuard.APPLE_TOKEN_LIFECYCLE_SCHEMA_VERSION}'",
            postcondition,
        )
        assertTrue(postcondition > tableEnd)
        assertTrue(marker > postcondition)
        assertTrue(migration.contains("2026-07-24-push-reliability-v4"))
        assertTrue(migration.contains("indexed_columns = 'member_id,status,id'"))
        assertTrue(migration.contains("referenced_table_name IS NOT NULL"))
        assertTrue(migration.contains("durable receipts/retries must not have foreign keys"))
        assertTrue(migration.contains("ck_apple_provider_credentials_status"))
        assertTrue(migration.contains("apple_authorization_code_receipts"))
        assertTrue(migration.contains("column_name = 'authorization_code_hash'"))
        assertTrue(migration.contains("is_nullable = 'NO'"))
        assertTrue(migration.contains("uk_apple_authorization_receipts_code_hash"))
        assertTrue(migration.contains("indexed_columns = 'authorization_code_hash'"))
        assertTrue(migration.contains("information_schema.check_constraints"))
        assertTrue(migration.contains("CHECK accepts incomplete PENDING"))
        assertTrue(migration.contains("CHECK accepts identifying manual state"))
        assertTrue(migration.contains("CHECK accepts provider material in REVOKED"))
    }

    @Test
    fun `entity development schema and reviewed migration use the same state check`() {
        val migration = Files.readString(migrationPath)
        val schema = Files.readString(Path.of("src/main/resources/schema.sql"))
        val entityCheck = requireNotNull(
            AppleProviderCredential::class.java.getAnnotation(Check::class.java)
        ).constraints

        val migrationCheck = migration.extractCheck("ck_apple_provider_credentials_status")
        val schemaCheck = schema.extractCheck("ck_apple_provider_credentials_status")
        assertEquals(normalizeCheck(schemaCheck), normalizeCheck(migrationCheck))
        assertEquals(normalizeCheck(schemaCheck), normalizeCheck(entityCheck))
    }

    @Test
    fun `encrypted refresh token uses the reviewed ASCII varchar contract`() {
        val expectedType = "VARCHAR(16384) CHARACTER SET ascii COLLATE ascii_bin"
        val portableEntityType =
            "VARCHAR(16384) /*!40100 CHARACTER SET ascii COLLATE ascii_bin */"
        val entityColumn = requireNotNull(
            AppleProviderCredential::class.java
                .getDeclaredField("encryptedRefreshToken")
                .getAnnotation(Column::class.java)
        )

        assertEquals(16384, entityColumn.length)
        assertEquals(portableEntityType, entityColumn.columnDefinition)
        assertEquals(
            expectedType,
            entityColumn.columnDefinition
                .replace("/*!40100 ", "")
                .replace(" */", ""),
        )
        listOf(
            Files.readString(migrationPath),
            Files.readString(Path.of("src/main/resources/schema.sql")),
        ).forEach { ddl ->
            assertTrue(ddl.contains("encrypted_refresh_token $expectedType"))
        }
    }

    private fun String.extractCheck(name: String): String {
        val marker = "CONSTRAINT $name CHECK ("
        val start = indexOf(marker)
        check(start >= 0) { "missing $name" }
        val expressionStart = start + marker.length
        var depth = 1
        for (index in expressionStart until length) {
            when (this[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return substring(expressionStart, index)
                }
            }
        }
        error("unterminated $name")
    }

    private fun normalizeCheck(value: String): String =
        value.lowercase().replace(Regex("\\s+"), "")
}
