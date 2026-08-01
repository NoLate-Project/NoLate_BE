package com.noLate.notification.domain

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

class DepartureAlarmFireEvidenceMySqlMigrationTest {
    @Test
    fun `migration creates constrained evidence schema and rejects reapplication`() {
        assumeTrue(dockerAvailable(), "Docker is required for MySQL 8.4 verification")
        val containerName = "nolate-alarm-fire-migration-${UUID.randomUUID()}"
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
        check(started.success) { "MySQL 8.4 container did not start: ${started.output.take(500)}" }

        try {
            waitForMySql(containerName)
            val migration = Files.readString(
                Path.of("docs/schedule/migrations/2026-08-01-departure-alarm-fire-evidence.sql"),
            )
            val applied = mysql(containerName, "$PREDECESSOR_SCHEMA\n$migration")
            check(applied.success) {
                "Reviewed alarm fire evidence migration failed on MySQL 8.4: " +
                    applied.output.take(2_000)
            }

            assertEquals(EXPECTED_COLUMNS, columnNames(containerName))
            assertEquals(EXPECTED_INDEXES, indexDefinitions(containerName))
            assertEquals("1", markerCount(containerName))

            val validInsert = mysql(containerName, VALID_INSERT)
            assertTrue(validInsert.success, validInsert.output.take(500))
            val duplicateSemantic = mysql(
                containerName,
                VALID_INSERT.replace(
                    "550e8400-e29b-41d4-a716-446655440000",
                    "550e8400-e29b-41d4-a716-446655440001",
                ),
            )
            assertFalse(duplicateSemantic.success, "Same-trigger semantic duplicate unexpectedly inserted")
            val snoozedFire = mysql(
                containerName,
                VALID_INSERT
                    .replace("550e8400-e29b-41d4-a716-446655440000", "550e8400-e29b-41d4-a716-446655440003")
                    .replace("'2026-08-01 03:00:00.000000'", "'2026-08-01 03:05:00.000000'")
                    .replace("'2026-08-01 03:00:04.000000'", "'2026-08-01 03:05:04.000000'"),
            )
            assertTrue(snoozedFire.success, "A later snooze fire in the same generation was lost")
            val invalidRelation = mysql(
                containerName,
                VALID_INSERT
                    .replace("550e8400-e29b-41d4-a716-446655440000", "550e8400-e29b-41d4-a716-446655440002")
                    .replace("a".repeat(64), "b".repeat(64))
                    .replace("'CURRENT'", "'STALE'"),
            )
            assertFalse(invalidRelation.success, "Invalid generation relation unexpectedly inserted")
            val invalidTimingBasis = mysql(
                containerName,
                VALID_INSERT
                    .replace("550e8400-e29b-41d4-a716-446655440000", "550e8400-e29b-41d4-a716-446655440004")
                    .replace("a".repeat(64), "c".repeat(64))
                    .replace("'EXACT_CALLBACK'", "'CLIENT_GUESSED'"),
            )
            assertFalse(invalidTimingBasis.success, "Invalid timing basis unexpectedly inserted")

            val receiptMigration = Files.readString(
                Path.of(
                    "docs/schedule/migrations/" +
                        "2026-08-01-departure-alarm-schedule-receipts.sql",
                ),
            )
            val receiptApplied = mysql(containerName, receiptMigration)
            assertTrue(
                receiptApplied.success,
                "Reviewed alarm receipt migration failed: ${receiptApplied.output.take(2_000)}",
            )
            assertEquals(EXPECTED_RECEIPT_COLUMNS, receiptColumnNames(containerName))
            assertEquals(EXPECTED_RECEIPT_INDEXES, receiptIndexDefinitions(containerName))
            assertEquals("1", receiptMarkerCount(containerName))
            assertTrue(mysql(containerName, VALID_RECEIPT_INSERT).success)
            assertFalse(
                mysql(
                    containerName,
                    VALID_RECEIPT_INSERT
                        .replace("550e8400-e29b-41d4-a716-446655440100", "550e8400-e29b-41d4-a716-446655440101")
                        .replace("'SCHEDULED', TRUE, TRUE", "'SCHEDULED', FALSE, FALSE"),
                ).success,
                "Invalid scheduled receipt shape unexpectedly inserted",
            )
            val receiptReapplied = mysql(containerName, receiptMigration)
            assertFalse(receiptReapplied.success, "Receipt migration unexpectedly accepted reapplication")
            assertTrue(
                receiptReapplied.output.contains(
                    "alarm receipt migration blocked: partial or applied schema requires inspection",
                ),
                "Unexpected receipt reapplication failure: ${receiptReapplied.output.take(1_000)}",
            )

            val reapplied = mysql(containerName, migration)
            assertFalse(reapplied.success, "Migration unexpectedly accepted reapplication")
            assertTrue(
                reapplied.output.contains(
                    "alarm fire evidence migration blocked: partial or applied schema requires inspection",
                ),
                "Unexpected reapplication failure: ${reapplied.output.take(1_000)}",
            )
            assertEquals("1", markerCount(containerName))
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

    private fun columnNames(containerName: String): List<String> = queryLines(
        containerName,
        """
        SELECT column_name
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_fire_events'
        ORDER BY ordinal_position;
        """.trimIndent(),
    )

    private fun indexDefinitions(containerName: String): Set<String> = queryLines(
        containerName,
        """
        SELECT CONCAT(
            index_name,
            '|',
            IF(MIN(non_unique) = 0, 'UNIQUE', 'NON_UNIQUE'),
            '|',
            GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
        )
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_fire_events'
        GROUP BY index_name
        ORDER BY index_name;
        """.trimIndent(),
    ).toSet()

    private fun markerCount(containerName: String): String = queryLines(
        containerName,
        """
        SELECT COUNT(*)
        FROM application_schema_migrations
        WHERE version = '2026-08-01-departure-alarm-fire-evidence-v1';
        """.trimIndent(),
    ).single()

    private fun receiptColumnNames(containerName: String): List<String> = queryLines(
        containerName,
        """
        SELECT column_name
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_schedule_receipts'
        ORDER BY ordinal_position;
        """.trimIndent(),
    )

    private fun receiptIndexDefinitions(containerName: String): Set<String> = queryLines(
        containerName,
        """
        SELECT CONCAT(
            index_name, '|',
            IF(MIN(non_unique) = 0, 'UNIQUE', 'NON_UNIQUE'), '|',
            GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
        )
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'departure_alarm_schedule_receipts'
        GROUP BY index_name
        ORDER BY index_name;
        """.trimIndent(),
    ).toSet()

    private fun receiptMarkerCount(containerName: String): String = queryLines(
        containerName,
        """
        SELECT COUNT(*)
        FROM application_schema_migrations
        WHERE version = '2026-08-01-departure-alarm-schedule-receipts-v1';
        """.trimIndent(),
    ).single()

    private fun queryLines(containerName: String, sql: String): List<String> {
        val result = mysql(containerName, sql)
        check(result.success) { "MySQL metadata query failed: ${result.output.take(1_000)}" }
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
        const val DATABASE = "nolate_alarm_fire_migration"

        val EXPECTED_COLUMNS = listOf(
            "id",
            "member_id",
            "client_event_id",
            "device_fingerprint",
            "alarm_id",
            "schedule_id",
            "generation",
            "desired_generation_at_receipt",
            "desired_operation_at_receipt",
            "generation_relation",
            "scheduled_for",
            "source_trigger_at",
            "client_occurred_at",
            "timing_basis",
            "fire_delay_seconds",
            "server_recorded_at",
        )

        val EXPECTED_INDEXES = setOf(
            "PRIMARY|UNIQUE|id",
            "uk_departure_alarm_fire_member_event|UNIQUE|member_id,client_event_id",
            "uk_departure_alarm_fire_member_device_trigger|UNIQUE|member_id,device_fingerprint,alarm_id,generation,scheduled_for",
            "idx_departure_alarm_fire_recorded_at|NON_UNIQUE|server_recorded_at,id",
            "idx_departure_alarm_fire_member|NON_UNIQUE|member_id,id",
            "idx_departure_alarm_fire_schedule|NON_UNIQUE|schedule_id,server_recorded_at",
        )

        val EXPECTED_RECEIPT_COLUMNS = listOf(
            "id", "member_id", "client_receipt_id", "device_fingerprint",
            "command_receipt_key", "alarm_id",
            "schedule_id", "generation", "desired_generation_at_receipt",
            "desired_operation_at_receipt", "generation_relation", "operation", "trigger_at",
            "outcome", "applied", "scheduled", "platform", "delivery_mode", "source",
            "failure_reason",
            "client_occurred_at", "server_recorded_at",
        )

        val EXPECTED_RECEIPT_INDEXES = setOf(
            "PRIMARY|UNIQUE|id",
            "uk_departure_alarm_receipt_member_client|UNIQUE|member_id,client_receipt_id",
            "uk_departure_alarm_receipt_member_device_command|UNIQUE|member_id,device_fingerprint,command_receipt_key",
            "idx_departure_alarm_receipt_cohort|NON_UNIQUE|outcome,trigger_at,platform,delivery_mode,server_recorded_at",
            "idx_departure_alarm_receipt_schedule|NON_UNIQUE|schedule_id,server_recorded_at",
            "idx_departure_alarm_receipt_member|NON_UNIQUE|member_id,id",
        )

        val PREDECESSOR_SCHEMA =
            """
            CREATE TABLE application_schema_migrations (
                version VARCHAR(100) NOT NULL PRIMARY KEY,
                description VARCHAR(255) NOT NULL,
                applied_at DATETIME(6) NOT NULL
            );
            INSERT INTO application_schema_migrations(version, description, applied_at)
            VALUES ('2026-07-31-push-eta-trust-v1', 'test prerequisite', CURRENT_TIMESTAMP(6));
            CREATE TABLE departure_alarm_sync_state (id BIGINT NOT NULL PRIMARY KEY);
            """.trimIndent()

        val VALID_INSERT =
            """
            INSERT INTO departure_alarm_fire_events (
                member_id, client_event_id, device_fingerprint, alarm_id, schedule_id,
                generation, desired_generation_at_receipt, desired_operation_at_receipt,
                generation_relation, scheduled_for, source_trigger_at, client_occurred_at,
                timing_basis, fire_delay_seconds, server_recorded_at
            ) VALUES (
                17, '550e8400-e29b-41d4-a716-446655440000', '${"a".repeat(64)}',
                'schedule:41:member:17', 41, 3, 3, 'UPSERT', 'CURRENT',
                '2026-08-01 03:00:00.000000', '2026-08-01 02:55:00.000000',
                '2026-08-01 03:00:04.000000', 'EXACT_CALLBACK', 4,
                '2026-08-01 03:01:00.000000'
            );
            """.trimIndent()

        val VALID_RECEIPT_INSERT =
            """
            INSERT INTO departure_alarm_schedule_receipts (
                member_id, client_receipt_id, device_fingerprint, command_receipt_key,
                alarm_id, schedule_id,
                generation, desired_generation_at_receipt, desired_operation_at_receipt,
                generation_relation, operation, trigger_at, outcome, applied, scheduled,
                platform, delivery_mode, source, failure_reason,
                client_occurred_at, server_recorded_at
            ) VALUES (
                17, '550e8400-e29b-41d4-a716-446655440100', '${"c".repeat(64)}',
                '${"d".repeat(64)}',
                'schedule:41:member:17', 41, 3, 3, 'UPSERT', 'CURRENT', 'UPSERT',
                '2026-08-01 04:00:00.000000', 'SCHEDULED', TRUE, TRUE,
                'ANDROID', 'ANDROID_EXACT', 'SNAPSHOT', NULL,
                '2026-08-01 03:00:00.000000', '2026-08-01 03:00:01.000000'
            );
            """.trimIndent()
    }
}
