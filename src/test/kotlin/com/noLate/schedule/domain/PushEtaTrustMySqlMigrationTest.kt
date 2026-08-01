package com.noLate.schedule.domain

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

/**
 * Applies the reviewed push/ETA trust migration through MySQL's own CLI against MySQL 8.4.
 * Docker CLI is used for the same reason as [com.noLate.auth.apple.AppleTokenLifecycleMySqlMigrationTest]:
 * the repository's current Testcontainers API negotiation is rejected by newer Docker Engines.
 * A host without Docker records an explicit JUnit skip, never a false-positive verification pass.
 */
class PushEtaTrustMySqlMigrationTest {
    @Test
    fun `migration creates exact trust schema and rejects reapplication`() {
        assumeTrue(dockerAvailable(), "Docker is required for MySQL 8.4 verification")
        val containerName = "nolate-push-eta-migration-${UUID.randomUUID()}"
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
            "MYSQL_DATABASE=nolate_push_eta_migration",
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
            val version = mysql(containerName, "SELECT VERSION();")
            assertTrue(version.success, version.output.take(500))
            assertTrue(
                version.output.lineSequence().map(String::trim).any { it.startsWith("8.4.") },
                "Expected MySQL 8.4 but received: ${version.output.take(120)}",
            )

            val migration = Files.readString(
                Path.of("docs/schedule/migrations/2026-07-31-push-eta-trust.sql"),
            )
            val applied = mysql(containerName, "$PREDECESSOR_SCHEMA\n$migration")
            check(applied.success) {
                "Reviewed push/ETA trust migration failed on MySQL 8.4: " +
                    applied.output.take(2_000)
            }

            assertEquals(
                OBSERVATION_COLUMNS,
                columnNames(containerName, "schedule_eta_accuracy_observations"),
            )
            assertEquals(
                EXPECTED_INDEXES,
                indexDefinitions(containerName, "schedule_eta_accuracy_observations"),
            )
            assertEquals(
                setOf("fk_eta_accuracy_schedule|schedule_id|schedules|id|CASCADE"),
                foreignKeyDefinitions(containerName, "schedule_eta_accuracy_observations"),
            )
            assertEquals(
                EXPECTED_CHECK_CONSTRAINTS,
                checkConstraintNames(containerName, "schedule_eta_accuracy_observations"),
            )
            assertEquals(
                SNAPSHOT_COLUMNS,
                columnNames(containerName, "schedule_departure_statuses")
                    .filter { it.startsWith("eta_snapshot_") },
            )
            assertEquals(
                OBSERVATION_FUNNEL_COLUMNS,
                columnNames(containerName, "schedule_departure_statuses")
                    .filter { it.startsWith("eta_observation_") },
            )
            assertEquals(
                ACK_COLUMNS,
                columnNames(containerName, "push_deliveries")
                    .filter { it in ACK_COLUMNS },
            )
            assertEquals(
                listOf(
                    "last_predicted_arrival_at",
                    "last_eta_travel_mode",
                    "last_eta_provider_fetched_at",
                    "last_eta_algorithm_version",
                ),
                columnNames(containerName, "schedule_push_job")
                    .filter {
                        it.startsWith("last_predicted_") ||
                            it == "last_eta_travel_mode" ||
                            it == "last_eta_provider_fetched_at" ||
                            it == "last_eta_algorithm_version"
                    },
            )
            assertEquals("1", markerCount(containerName))

            assertDatabaseObservationQualityGuards(containerName)

            val reapplied = mysql(containerName, migration)
            assertFalse(
                reapplied.success,
                "Fail-closed migration unexpectedly accepted reapplication",
            )
            assertTrue(
                reapplied.output.contains(
                    "push/ETA trust migration blocked: partial or applied schema requires inspection",
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
            val ping = command(
                "docker",
                "exec",
                containerName,
                "mysql",
                "--batch",
                "--skip-column-names",
                "-unolate",
                "-pnolate",
                DATABASE,
                "--execute",
                "SELECT 1",
                timeoutSeconds = 5,
            )
            if (ping.success) return
            Thread.sleep(500)
        }
        error("MySQL 8.4 did not become ready within 60 seconds")
    }

    private fun columnNames(containerName: String, tableName: String): List<String> =
        queryLines(
            containerName,
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = '$tableName'
            ORDER BY ordinal_position;
            """.trimIndent(),
        )

    private fun indexDefinitions(containerName: String, tableName: String): Set<String> =
        queryLines(
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
              AND table_name = '$tableName'
            GROUP BY index_name
            ORDER BY index_name;
            """.trimIndent(),
        ).toSet()

    private fun foreignKeyDefinitions(containerName: String, tableName: String): Set<String> =
        queryLines(
            containerName,
            """
            SELECT CONCAT(
                rc.constraint_name,
                '|',
                kcu.column_name,
                '|',
                kcu.referenced_table_name,
                '|',
                kcu.referenced_column_name,
                '|',
                rc.delete_rule
            )
            FROM information_schema.referential_constraints rc
            JOIN information_schema.key_column_usage kcu
              ON kcu.constraint_schema = rc.constraint_schema
             AND kcu.table_name = rc.table_name
             AND kcu.constraint_name = rc.constraint_name
            WHERE rc.constraint_schema = DATABASE()
              AND rc.table_name = '$tableName'
            ORDER BY rc.constraint_name, kcu.ordinal_position;
            """.trimIndent(),
        ).toSet()

    private fun checkConstraintNames(containerName: String, tableName: String): Set<String> =
        queryLines(
            containerName,
            """
            SELECT constraint_name
            FROM information_schema.table_constraints
            WHERE constraint_schema = DATABASE()
              AND table_name = '$tableName'
              AND constraint_type = 'CHECK'
            ORDER BY constraint_name;
            """.trimIndent(),
        ).toSet()

    private fun markerCount(containerName: String): String =
        queryLines(
            containerName,
            """
            SELECT COUNT(*)
            FROM application_schema_migrations
            WHERE version = '2026-07-31-push-eta-trust-v1';
            """.trimIndent(),
        ).single()

    private fun assertDatabaseObservationQualityGuards(containerName: String) {
        val schedules = mysql(
            containerName,
            "INSERT INTO schedules(id) VALUES (1), (2), (3), (4), (5), (6), (7), (8), (9), (10), (11), (12), (13), (14), (15), (16);",
        )
        assertTrue(schedules.success, schedules.output.take(500))

        val valid = mysql(
            containerName,
            observationInsert(scheduleId = 1, memberId = 7),
        )
        assertTrue(valid.success, valid.output.take(500))

        val adjusted = mysql(
            containerName,
            observationInsert(
                scheduleId = 2,
                memberId = 8,
                observationSource = "USER_ADJUSTED",
                precisionSeconds = 60,
                adjustmentSeconds = 300,
            ),
        )
        assertTrue(adjusted.success, adjusted.output.take(500))

        val futureVerified = mysql(
            containerName,
            observationInsert(
                scheduleId = 3,
                memberId = 9,
                observationSource = "GEOFENCE",
                observationVerification = "VERIFIED_GEOFENCE",
                clientAppVersion = "1.2.0",
                clientBuildVersion = "42",
                backendCohortVersion = "api-2026.08.01",
                accuracyEligible = true,
                accuracyEligibilityReason = "ELIGIBLE",
            ),
        )
        assertTrue(futureVerified.success, futureVerified.output.take(500))

        listOf(
            observationInsert(scheduleId = 4, memberId = 10, observationSource = "UNKNOWN"),
            observationInsert(scheduleId = 5, memberId = 11, precisionSeconds = 0),
            observationInsert(
                scheduleId = 6,
                memberId = 12,
                departedAt = "2026-07-31 04:01:00.000000",
                actualArrivalAt = "2026-07-31 04:00:00.000000",
            ),
            observationInsert(
                scheduleId = 7,
                memberId = 13,
                observationSource = "USER_ADJUSTED",
                precisionSeconds = 60,
            ),
            observationInsert(scheduleId = 8, memberId = 14, adjustmentSeconds = 300),
            observationInsert(
                scheduleId = 9,
                memberId = 15,
                observationSource = "USER_ADJUSTED",
                precisionSeconds = 60,
                adjustmentSeconds = 301,
            ),
            observationInsert(
                scheduleId = 10,
                memberId = 16,
                observationSource = "USER_ADJUSTED",
                precisionSeconds = 60,
                adjustmentSeconds = 3_660,
            ),
            observationInsert(
                scheduleId = 11,
                memberId = 17,
                observationSource = "GEOFENCE",
                accuracyEligible = true,
                accuracyEligibilityReason = "ELIGIBLE",
            ),
            observationInsert(
                scheduleId = 12,
                memberId = 18,
                accuracyEligible = false,
                accuracyEligibilityReason = "ELIGIBLE",
            ),
            observationInsert(
                scheduleId = 13,
                memberId = 19,
                observationSource = "GEOFENCE",
                observationVerification = "VERIFIED_GEOFENCE",
                accuracyEligible = true,
                accuracyEligibilityReason = "ELIGIBLE",
            ),
            observationInsert(
                scheduleId = 14,
                memberId = 20,
                observationSource = "GEOFENCE",
                observationVerification = "VERIFIED_GEOFENCE",
                clientAppVersion = "1.2.0",
                clientBuildVersion = "42",
                backendCohortVersion = "unversioned",
                accuracyEligible = true,
                accuracyEligibilityReason = "ELIGIBLE",
            ),
            observationInsert(
                scheduleId = 15,
                memberId = 21,
                observationSource = "GEOFENCE",
                observationVerification = "VERIFIED_GEOFENCE",
                clientAppVersion = "1.2.0",
                clientBuildVersion = "42",
                algorithmVersion = "UNKNOWN",
                accuracyEligible = true,
                accuracyEligibilityReason = "ELIGIBLE",
            ),
            observationInsert(
                scheduleId = 16,
                memberId = 22,
                observationSource = "GEOFENCE",
                observationVerification = "VERIFIED_GEOFENCE",
                clientAppVersion = "1.2.0",
                clientBuildVersion = "42",
                predictionBasis = "DEPARTURE_ANCHORED_DURATION",
                accuracyEligible = true,
                accuracyEligibilityReason = "ELIGIBLE",
            ),
        ).forEach { invalidInsert ->
            val rejected = mysql(containerName, invalidInsert)
            assertFalse(
                rejected.success,
                "MySQL unexpectedly accepted invalid arrival ground truth",
            )
        }
    }

    private fun observationInsert(
        scheduleId: Long,
        memberId: Long,
        observationSource: String = "USER_NOW",
        observationVerification: String = "UNVERIFIED_CLIENT",
        precisionSeconds: Int = 30,
        adjustmentSeconds: Int? = null,
        clientAppVersion: String? = null,
        clientBuildVersion: String? = null,
        backendCohortVersion: String = "integration-test",
        algorithmVersion: String = "TRANSIT_REALTIME_V2",
        predictionBasis: String = "PROVIDER_ABSOLUTE",
        accuracyEligible: Boolean = false,
        accuracyEligibilityReason: String = when (observationSource) {
            "USER_ADJUSTED" -> "UNVERIFIED_USER_ADJUSTED"
            "GEOFENCE" -> "UNVERIFIED_GEOFENCE"
            else -> "UNVERIFIED_USER_NOW"
        },
        departedAt: String = "2026-07-31 03:00:00.000000",
        actualArrivalAt: String = "2026-07-31 04:00:00.000000",
    ): String =
        """
        INSERT INTO schedule_eta_accuracy_observations (
            schedule_id, member_id, push_job_id, departed_at, prediction_evaluated_at,
            predicted_arrival_at, recommended_departure_at, target_arrival_at,
            actual_arrival_at, observation_verification, observation_source,
            precision_seconds, adjustment_seconds, client_app_version, client_build_version,
            backend_cohort_version, eligibility_policy_version,
            eta_source, eta_stale,
            travel_minutes, prediction_basis, travel_mode, provider_id, algorithm_version,
            provider_fetched_at, predicted_on_time, actual_on_time, on_time_outcome,
            departure_offset_seconds, actual_travel_seconds, report_delay_seconds,
            accuracy_eligible, accuracy_eligibility_reason, signed_error_seconds,
            absolute_error_seconds, recorded_at
        ) VALUES (
            $scheduleId, $memberId, NULL, '$departedAt', '2026-07-31 02:59:00.000000',
            '2026-07-31 04:00:00.000000', '2026-07-31 03:00:00.000000',
            '2026-07-31 04:00:00.000000', '$actualArrivalAt', '$observationVerification',
            '$observationSource', $precisionSeconds, ${adjustmentSeconds ?: "NULL"},
            ${clientAppVersion?.let { "'$it'" } ?: "NULL"},
            ${clientBuildVersion?.let { "'$it'" } ?: "NULL"},
            '$backendCohortVersion', 'SELF_REPORT_DIAGNOSTIC_V2',
            'LIVE_PROVIDER', FALSE, 60,
            '$predictionBasis', 'TRANSIT',
            'ODSAY_TRANSIT', '$algorithmVersion', '2026-07-31 02:59:00.000000',
            TRUE, TRUE, 'PREDICTED_ON_TIME_ACTUAL_ON_TIME', 0, 3600, 1,
            $accuracyEligible, '$accuracyEligibilityReason', 0, 0,
            '2026-07-31 04:00:01.000000'
        );
        """.trimIndent()

    private fun queryLines(containerName: String, sql: String): List<String> {
        val result = mysql(containerName, sql)
        check(result.success) { "MySQL metadata query failed: ${result.output.take(1_000)}" }
        return result.output
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot { it.startsWith("mysql: [Warning]") }
            .toList()
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
            DATABASE,
            stdin = sql,
            timeoutSeconds = 60,
        )

    private fun dockerAvailable(): Boolean =
        try {
            command("docker", "info").success
        } catch (_: IOException) {
            false
        }

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

    private companion object {
        const val DATABASE = "nolate_push_eta_migration"

        val OBSERVATION_COLUMNS = listOf(
            "id",
            "schedule_id",
            "member_id",
            "push_job_id",
            "departed_at",
            "prediction_evaluated_at",
            "predicted_arrival_at",
            "recommended_departure_at",
            "target_arrival_at",
            "actual_arrival_at",
            "observation_verification",
            "observation_source",
            "precision_seconds",
            "adjustment_seconds",
            "client_app_version",
            "client_build_version",
            "backend_cohort_version",
            "eligibility_policy_version",
            "eta_source",
            "eta_stale",
            "travel_minutes",
            "prediction_basis",
            "travel_mode",
            "provider_id",
            "algorithm_version",
            "provider_fetched_at",
            "predicted_on_time",
            "actual_on_time",
            "on_time_outcome",
            "departure_offset_seconds",
            "actual_travel_seconds",
            "report_delay_seconds",
            "accuracy_eligible",
            "accuracy_eligibility_reason",
            "signed_error_seconds",
            "absolute_error_seconds",
            "recorded_at",
        )

        val EXPECTED_INDEXES = setOf(
            "PRIMARY|UNIQUE|id",
            "uk_eta_accuracy_schedule_member|UNIQUE|schedule_id,member_id",
            "idx_eta_accuracy_recorded_at|NON_UNIQUE|recorded_at",
            "idx_eta_accuracy_source|NON_UNIQUE|eta_source,recorded_at",
            "idx_eta_accuracy_observation_quality|NON_UNIQUE|accuracy_eligible,observation_source,precision_seconds,recorded_at",
            "idx_eta_accuracy_provenance|NON_UNIQUE|algorithm_version,travel_mode,provider_id,prediction_basis,recorded_at",
            "idx_eta_accuracy_cohort|NON_UNIQUE|backend_cohort_version,client_app_version,algorithm_version,recorded_at",
            "idx_eta_accuracy_member|NON_UNIQUE|member_id,id",
        )

        val EXPECTED_CHECK_CONSTRAINTS = setOf(
            "chk_eta_accuracy_travel_minutes",
            "chk_eta_accuracy_observation_source",
            "chk_eta_accuracy_observation_verification",
            "chk_eta_accuracy_precision_seconds",
            "chk_eta_accuracy_observation_shape",
            "chk_eta_accuracy_client_cohort",
            "chk_eta_accuracy_backend_cohort",
            "chk_eta_accuracy_eligibility_policy",
            "chk_eta_accuracy_eligibility_reason",
            "chk_eta_accuracy_eligibility_consistency",
            "chk_eta_accuracy_eligible_provenance",
            "chk_eta_accuracy_actual_after_departure",
            "chk_eta_accuracy_actual_travel",
            "chk_eta_accuracy_report_delay",
            "chk_eta_accuracy_absolute_error",
            "chk_eta_accuracy_predicted_on_time",
            "chk_eta_accuracy_actual_on_time",
            "chk_eta_accuracy_on_time_outcome",
        )

        val SNAPSHOT_COLUMNS = listOf(
            "eta_snapshot_push_job_id",
            "eta_snapshot_evaluated_at",
            "eta_snapshot_recommended_departure_at",
            "eta_snapshot_predicted_arrival_at",
            "eta_snapshot_source",
            "eta_snapshot_stale",
            "eta_snapshot_travel_minutes",
            "eta_snapshot_prediction_basis",
            "eta_snapshot_travel_mode",
            "eta_snapshot_provider_id",
            "eta_snapshot_target_arrival_at",
            "eta_snapshot_on_time_arrival_possible",
            "eta_snapshot_algorithm_version",
            "eta_snapshot_provider_fetched_at",
        )

        val OBSERVATION_FUNNEL_COLUMNS = listOf(
            "eta_observation_exposed_at",
            "eta_observation_exposed_client_app_version",
            "eta_observation_exposed_client_build_version",
            "eta_observation_exposed_ux_variant",
            "eta_observation_prompted_at",
            "eta_observation_prompted_client_app_version",
            "eta_observation_prompted_client_build_version",
            "eta_observation_prompted_ux_variant",
            "eta_observation_responded_at",
        )

        val ACK_COLUMNS = listOf(
            "client_received_at",
            "client_presented_at",
            "alarm_scheduled_at",
            "alarm_fired_at",
            "client_actioned_at",
            "client_ack_recorded_at",
        )

        val PREDECESSOR_SCHEMA =
            """
            CREATE TABLE application_schema_migrations (
                version VARCHAR(100) NOT NULL PRIMARY KEY,
                description VARCHAR(255) NOT NULL,
                applied_at DATETIME(6) NOT NULL
            );
            INSERT INTO application_schema_migrations(version, description, applied_at)
            VALUES (
                '2026-07-29-departure-alarm-sync-v1',
                'test prerequisite',
                CURRENT_TIMESTAMP(6)
            );

            CREATE TABLE schedules (
                id BIGINT NOT NULL PRIMARY KEY
            );
            CREATE TABLE schedule_push_job (
                id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                last_eta_failure_reason VARCHAR(255) NULL
            );
            CREATE TABLE schedule_departure_statuses (
                id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                departed_at DATETIME(6) NULL
            );
            CREATE TABLE push_deliveries (
                id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                error_message TEXT NULL
            );
            """.trimIndent()
    }
}
