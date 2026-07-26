package com.noLate.auth.apple

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Applies the reviewed file through MySQL's own CLI to actual MySQL 8.4. Docker CLI is used
 * instead of docker-java because Docker Engine 29 rejects the old API negotiation embedded in
 * the repository's current Testcontainers dependency. A host without Docker records an explicit
 * JUnit skip; it must never be reported as MySQL verification success.
 */
class AppleTokenLifecycleMySqlMigrationTest {
    @Test
    fun `reviewed migration applies and MySQL rejects poison pending envelope`() {
        assumeTrue(command("docker", "info").success, "Docker is required for MySQL 8.4 verification")
        val containerName = "nolate-apple-migration-${UUID.randomUUID()}"
        val started = command(
            "docker",
            "run",
            "--detach",
            "--rm",
            "--name",
            containerName,
            "--env",
            "MYSQL_ROOT_PASSWORD=nolate-root",
            "--env",
            "MYSQL_DATABASE=nolate_apple_migration",
            "--env",
            "MYSQL_USER=nolate",
            "--env",
            "MYSQL_PASSWORD=nolate",
            "mysql:8.4",
            timeoutSeconds = 120,
        )
        check(started.success) { "MySQL 8.4 container did not start: ${started.output.take(500)}" }

        try {
            waitForMySql(containerName)
            val version = mysql(containerName, "SELECT VERSION();")
            assertTrue(version.success)
            assertTrue(
                version.output.lineSequence().map(String::trim).any { it.startsWith("8.4.") },
                "Expected MySQL 8.4 but received: ${version.output.take(120)}",
            )
            val migration = Files.readString(
                Path.of("docs/member/migrations/2026-07-26-apple-token-lifecycle.sql")
            )
            val prerequisite =
                """
                CREATE TABLE application_schema_migrations (
                    version VARCHAR(100) NOT NULL PRIMARY KEY,
                    description VARCHAR(255) NOT NULL,
                    applied_at DATETIME(6) NOT NULL
                );
                INSERT INTO application_schema_migrations(version, description, applied_at)
                VALUES (
                    '2026-07-24-push-reliability-v4',
                    'test prerequisite',
                    CURRENT_TIMESTAMP(6)
                );
                """.trimIndent()
            val applied = mysql(containerName, "$prerequisite\n$migration")
            check(applied.success) {
                "Reviewed Apple migration failed on MySQL 8.4: ${applied.output.take(1_000)}"
            }

            val poison = mysql(
                containerName,
                """
                INSERT INTO apple_provider_credentials (
                    credential_key,
                    source_receipt_key,
                    client_id,
                    status,
                    attempt_count,
                    next_attempt_at,
                    version,
                    deleted
                ) VALUES (
                    'poison-pending-envelope',
                    'poison-pending-receipt',
                    'com.nolate.test',
                    'PENDING',
                    0,
                    CURRENT_TIMESTAMP(6),
                    0,
                    FALSE
                );
                """.trimIndent(),
            )
            assertEquals(false, poison.success, "MySQL accepted a malformed PENDING envelope")

            val processingPoison = mysql(
                containerName,
                """
                INSERT INTO apple_provider_credentials (
                    credential_key,
                    source_receipt_key,
                    client_id,
                    status,
                    attempt_count,
                    next_attempt_at,
                    locked_at,
                    locked_by,
                    version,
                    deleted
                ) VALUES (
                    'poison-processing-envelope',
                    'poison-processing-receipt',
                    'com.nolate.test',
                    'PROCESSING',
                    1,
                    CURRENT_TIMESTAMP(6),
                    CURRENT_TIMESTAMP(6),
                    'dead-worker',
                    0,
                    FALSE
                );
                """.trimIndent(),
            )
            assertEquals(
                false,
                processingPoison.success,
                "MySQL accepted a malformed PROCESSING envelope",
            )

            val validManualAndChecks = mysql(
                containerName,
                """
                INSERT INTO apple_provider_credentials (
                    credential_key,
                    client_id,
                    status,
                    attempt_count,
                    last_failure_code,
                    version,
                    deleted
                ) VALUES (
                    'manual-action-tombstone',
                    'com.nolate.test',
                    'MANUAL_ACTION',
                    0,
                    'APPLE_MANUAL_DISCONNECT_REQUIRED',
                    0,
                    FALSE
                );
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE table_schema = DATABASE()
                  AND table_name = 'apple_provider_credentials'
                  AND constraint_name = 'ck_apple_provider_credentials_status'
                  AND constraint_type = 'CHECK';
                SELECT COUNT(*)
                FROM application_schema_migrations
                WHERE version = '2026-07-26-apple-token-lifecycle-v1';
                """.trimIndent(),
            )
            check(validManualAndChecks.success) {
                "Valid tombstone or migration postcondition failed: " +
                    validManualAndChecks.output.take(1_000)
            }
            val counts = validManualAndChecks.output
                .lineSequence()
                .map(String::trim)
                .filter { it.matches(Regex("\\d+")) }
                .toList()
            assertEquals(listOf("1", "1"), counts.takeLast(2))
        } finally {
            command("docker", "rm", "--force", containerName, timeoutSeconds = 30)
        }
    }

    private fun waitForMySql(containerName: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            val ping = command(
                "docker",
                "exec",
                containerName,
                "mysql",
                "--batch",
                "--skip-column-names",
                "-unolate",
                "-pnolate",
                "nolate_apple_migration",
                "--execute",
                "SELECT 1",
                timeoutSeconds = 5,
            )
            if (ping.success) return
            Thread.sleep(500)
        }
        error("MySQL 8.4 did not become ready within 60 seconds")
    }

    private fun mysql(containerName: String, sql: String): CommandResult =
        command(
            "docker",
            "exec",
            "--interactive",
            containerName,
            "mysql",
            "--batch",
            "--skip-column-names",
            "-unolate",
            "-pnolate",
            "nolate_apple_migration",
            stdin = sql,
            timeoutSeconds = 60,
        )

    private fun command(
        vararg command: String,
        stdin: String? = null,
        timeoutSeconds: Long = 15,
    ): CommandResult {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        process.outputStream.use { output ->
            if (stdin != null) output.write(stdin.toByteArray(Charsets.UTF_8))
        }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return CommandResult(
            success = finished && process.exitValue() == 0,
            output = output,
        )
    }

    private data class CommandResult(
        val success: Boolean,
        val output: String,
    )
}
