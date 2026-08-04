package com.noLate.schedule.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

class ScheduleRouteOptimisticLockMigrationContractTest {
    private val migration = Files.readString(MIGRATION_PATH)

    @Test
    fun `migration is additive verified and idempotent by contract`() {
        val precondition = migration.indexOf(
            "CALL assert_schedule_route_version_preconditions()",
        )
        val ddl = migration.indexOf("ALTER TABLE schedule_routes ADD COLUMN version BIGINT")
        val postcondition = migration.indexOf(
            "CALL assert_schedule_route_version_postconditions()",
        )
        val marker = migration.indexOf(
            "INSERT INTO application_schema_migrations(version, description, applied_at)",
        )

        assertThat(precondition).isGreaterThanOrEqualTo(0)
        assertThat(ddl).isGreaterThan(precondition)
        assertThat(postcondition).isGreaterThan(ddl)
        assertThat(marker).isGreaterThan(postcondition)
        assertThat(migration).contains(
            "ADD COLUMN version BIGINT NOT NULL DEFAULT 0",
            "column_name = 'version'",
            "data_type = 'bigint'",
            "column_type = 'bigint'",
            "is_nullable = 'NO'",
            "column_default = '0'",
            "WHERE NOT EXISTS (",
            "2026-08-04-schedule-route-optimistic-lock-v1",
            "invalid legacy coordinates require explicit cleanup",
            "invalid_schedule_route_coordinate_count",
            "invalid_travel_plan_origin_coordinate_count",
        )
        assertThat(migration).doesNotContain("UPDATE schedule_routes SET version")
    }

    @Test
    fun `mysql 84 backfills existing rows and safely accepts reapplication`() {
        assumeTrue(dockerAvailable(), "Docker is required for MySQL 8.4 verification")
        val containerName = "nolate-route-version-migration-${UUID.randomUUID()}"
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
            "MYSQL_DATABASE=$DATABASE",
            "--env",
            "MYSQL_USER=nolate",
            "--env",
            "MYSQL_PASSWORD=nolate",
            "mysql:8.4",
            timeoutSeconds = 120,
        )
        check(started.success) {
            "MySQL 8.4 container did not start: ${started.output.take(500)}"
        }

        try {
            waitForMySql(containerName)
            val version = queryLines(containerName, "SELECT VERSION();")
            assertTrue(
                version.any { it.startsWith("8.4.") },
                "Expected MySQL 8.4 but received: $version",
            )

            val applied = mysql(containerName, "$PREDECESSOR_SCHEMA\n$migration")
            assertTrue(
                applied.success,
                "Schedule route version migration failed: ${applied.output.take(2_000)}",
            )
            assertEquals("bigint|bigint|NO|0", columnContract(containerName))
            assertEquals(
                listOf("1|legacy-one|0", "2|legacy-two|0"),
                routeVersions(containerName),
            )
            val firstMarker = marker(containerName)

            val advanced = mysql(
                containerName,
                """
                UPDATE schedule_routes SET version = 7 WHERE id = 1;
                INSERT INTO schedule_routes(route_name) VALUES ('new-route');
                """.trimIndent(),
            )
            assertTrue(advanced.success, advanced.output.take(500))

            val reapplied = mysql(containerName, migration)
            assertTrue(
                reapplied.success,
                "Idempotent reapplication failed: ${reapplied.output.take(2_000)}",
            )
            assertEquals("bigint|bigint|NO|0", columnContract(containerName))
            assertEquals(
                listOf("1|legacy-one|7", "2|legacy-two|0", "3|new-route|0"),
                routeVersions(containerName),
            )
            assertEquals(firstMarker, marker(containerName))

            val invalidLegacy = mysql(
                containerName,
                """
                INSERT INTO schedule_travel_plans(origin_lat, origin_lng)
                VALUES (37.5, NULL);
                """.trimIndent(),
            )
            assertTrue(invalidLegacy.success, invalidLegacy.output.take(500))
            val rejected = mysql(containerName, migration)
            assertFalse(rejected.success, "Invalid legacy coordinates unexpectedly passed preflight")
            assertTrue(
                rejected.output.contains("invalid legacy coordinates require explicit cleanup"),
                rejected.output.take(1_000),
            )
        } finally {
            command("docker", "rm", "--force", containerName, timeoutSeconds = 30)
        }
    }

    private fun waitForMySql(containerName: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            if (mysql(containerName, "SELECT 1;", timeoutSeconds = 5).success) return
            Thread.sleep(500)
        }
        error("MySQL 8.4 did not become ready within 60 seconds")
    }

    private fun columnContract(containerName: String): String = queryLines(
        containerName,
        """
        SELECT CONCAT(
            data_type, '|', column_type, '|', is_nullable, '|',
            COALESCE(column_default, '<NULL>')
        )
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'schedule_routes'
          AND column_name = 'version';
        """.trimIndent(),
    ).single()

    private fun routeVersions(containerName: String): List<String> = queryLines(
        containerName,
        """
        SELECT CONCAT(id, '|', route_name, '|', version)
        FROM schedule_routes
        ORDER BY id;
        """.trimIndent(),
    )

    private fun marker(containerName: String): String = queryLines(
        containerName,
        """
        SELECT CONCAT(
            COUNT(*), '|',
            MIN(description), '|',
            DATE_FORMAT(MIN(applied_at), '%Y-%m-%dT%H:%i:%s.%f')
        )
        FROM application_schema_migrations
        WHERE version = '2026-08-04-schedule-route-optimistic-lock-v1';
        """.trimIndent(),
    ).single()

    private fun queryLines(containerName: String, sql: String): List<String> {
        val result = mysql(containerName, sql)
        check(result.success) { "MySQL query failed: ${result.output.take(1_000)}" }
        return result.output.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot { it.startsWith("mysql: [Warning]") }
            .toList()
    }

    private fun mysql(
        containerName: String,
        sql: String,
        timeoutSeconds: Long = 60,
    ): CommandResult = command(
        "docker",
        "exec",
        "--interactive",
        containerName,
        "mysql",
        "--batch",
        "--skip-column-names",
        "-unolate",
        "-pnolate",
        DATABASE,
        stdin = sql,
        timeoutSeconds = timeoutSeconds,
    )

    private fun dockerAvailable(): Boolean = try {
        command("docker", "info").success
    } catch (_: IOException) {
        false
    }

    private fun command(
        vararg command: String,
        stdin: String? = null,
        timeoutSeconds: Long = 15,
    ): CommandResult {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.outputStream.use { output ->
            if (stdin != null) output.write(stdin.toByteArray(Charsets.UTF_8))
        }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return CommandResult(finished && process.exitValue() == 0, output)
    }

    private data class CommandResult(val success: Boolean, val output: String)

    private companion object {
        val MIGRATION_PATH: Path = Path.of(
            "docs/schedule/migrations/2026-08-04-schedule-route-optimistic-lock.sql",
        )
        const val DATABASE = "nolate_schedule_route_version_migration"

        val PREDECESSOR_SCHEMA =
            """
            CREATE TABLE application_schema_migrations (
                version VARCHAR(100) NOT NULL PRIMARY KEY,
                description VARCHAR(255) NOT NULL,
                applied_at DATETIME(6) NOT NULL
            );
            CREATE TABLE schedule_routes (
                id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                route_name VARCHAR(255) NULL,
                origin_lat DOUBLE NULL,
                origin_lng DOUBLE NULL,
                destination_lat DOUBLE NULL,
                destination_lng DOUBLE NULL
            );
            CREATE TABLE schedule_travel_plans (
                id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                origin_lat DOUBLE NULL,
                origin_lng DOUBLE NULL
            );
            INSERT INTO schedule_routes(route_name)
            VALUES ('legacy-one'), ('legacy-two');
            """.trimIndent()
    }
}
