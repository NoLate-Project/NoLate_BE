package com.noLate.schedule.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/**
 * 저장된 DDL 자체를 H2 MySQL mode에서 실행해 bootstrap과 수동 migration의 테이블 계약을 확인한다.
 * MySQL 전용 DELIMITER·검증 프로시저의 실제 실행 검증을 대신하지는 않는다.
 */
class ScheduleEtaEvaluationHistorySchemaTest {
    @ParameterizedTest
    @ValueSource(strings = ["migration", "bootstrap"])
    fun `DDL creates nullable calculation fields and per evaluation lookup indexes`(schemaSource: String) {
        withSchema(schemaSource) { connection ->
            val nullableByColumn = buildMap {
                connection.metaData.getColumns(null, null, TABLE_NAME.uppercase(), null).use { rows ->
                    while (rows.next()) {
                        put(
                            rows.getString("COLUMN_NAME").lowercase(),
                            rows.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                        )
                    }
                }
            }
            assertEquals(
                mapOf(
                    "id" to false,
                    "evaluation_id" to false,
                    "push_job_id" to false,
                    "schedule_id" to false,
                    "member_id" to false,
                    "evaluated_at" to false,
                    "eta_source" to false,
                    "eta_stale" to false,
                    "failure_reason" to true,
                    "travel_minutes" to false,
                    "recommended_departure_at" to true,
                    "predicted_arrival_at" to true,
                    "target_arrival_at" to false,
                    "provider_fetched_at" to true,
                ),
                nullableByColumn,
            )

            val indexes = linkedMapOf<String, MutableList<Pair<Int, String>>>()
            connection.metaData.getIndexInfo(null, null, TABLE_NAME.uppercase(), false, false).use { rows ->
                while (rows.next()) {
                    val name = rows.getString("INDEX_NAME")?.lowercase() ?: continue
                    indexes.getOrPut(name) { mutableListOf() }.add(
                        rows.getInt("ORDINAL_POSITION") to rows.getString("COLUMN_NAME").lowercase(),
                    )
                }
            }
            val lookupIndexes = indexes
                .filterKeys { it.startsWith("idx_eta_evaluation_") }
                .mapValues { (_, columns) -> columns.sortedBy { it.first }.map { it.second } }
            assertEquals(
                mapOf(
                    "idx_eta_evaluation_schedule_member_time" to
                        listOf("schedule_id", "member_id", "evaluated_at", "id"),
                    "idx_eta_evaluation_job_time" to listOf("push_job_id", "evaluated_at", "id"),
                    "idx_eta_evaluation_time" to listOf("evaluated_at", "id"),
                ),
                lookupIndexes,
            )
            connection.metaData.getImportedKeys(null, null, TABLE_NAME.uppercase()).use { rows ->
                assertFalse(rows.next(), "Snapshot IDs must not introduce implicit lifecycle cascades")
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["migration", "bootstrap"])
    fun `DDL keeps consecutive outcomes but rejects saving the same evaluation twice`(schemaSource: String) {
        withSchema(schemaSource) { connection ->
            val evaluationId = UUID.randomUUID().toString()
            insertEvaluation(connection, evaluationId, "SAVED_FALLBACK", "PROVIDER_TIMEOUT")
            insertEvaluation(connection, UUID.randomUUID().toString(), "LIVE_PROVIDER", null)

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT * FROM $TABLE_NAME ORDER BY evaluated_at, id",
                ).use { rows ->
                    rows.next()
                    assertEquals("PROVIDER_TIMEOUT", rows.getString("failure_reason"))
                    assertNull(rows.getTimestamp("recommended_departure_at"))
                    assertNull(rows.getTimestamp("predicted_arrival_at"))
                    assertNull(rows.getTimestamp("provider_fetched_at"))
                    rows.next()
                    assertEquals("LIVE_PROVIDER", rows.getString("eta_source"))
                    assertNull(rows.getString("failure_reason"))
                    assertEquals(123456, rows.getTimestamp("evaluated_at").nanos / 1000)
                    assertFalse(rows.next())
                }
            }

            val duplicate = assertThrows(SQLException::class.java) {
                insertEvaluation(connection, evaluationId, "SAVED_FALLBACK", "PROVIDER_TIMEOUT")
            }
            assertEquals("23505", duplicate.sqlState, "Evaluation UUID must be unique")
        }
    }

    private fun withSchema(source: String, block: (Connection) -> Unit) {
        val path = when (source) {
            "migration" -> Path.of("docs/schedule/migrations/2026-09-08-eta-evaluation-history.sql")
            "bootstrap" -> Path.of("src/main/resources/schema.sql")
            else -> error("Unknown schema source: $source")
        }
        val createTable = requireNotNull(
            Regex(
                "(?s)CREATE TABLE(?: IF NOT EXISTS)? $TABLE_NAME \\(.*?\\) ENGINE=InnoDB COMMENT='[^']*';",
            ).find(Files.readString(path)),
        ).value
        DriverManager.getConnection(
            "jdbc:h2:mem:eta-history-schema-${UUID.randomUUID()};MODE=MySQL",
            "sa",
            "",
        ).use { connection ->
            connection.createStatement().use { it.execute(createTable) }
            block(connection)
        }
    }

    private fun insertEvaluation(
        connection: Connection,
        evaluationId: String,
        source: String,
        reason: String?,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO schedule_eta_evaluation_history (
                evaluation_id, push_job_id, schedule_id, member_id, evaluated_at,
                eta_source, eta_stale, failure_reason, travel_minutes, target_arrival_at,
                provider_fetched_at
            ) VALUES (?, 10, 20, 30, '2026-09-08 08:00:00.123456', ?, ?, ?, 40,
                      '2026-09-08 09:00:00', ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, evaluationId)
            statement.setString(2, source)
            statement.setBoolean(3, reason != null)
            statement.setString(4, reason)
            statement.setTimestamp(
                5,
                if (source == "LIVE_PROVIDER") java.sql.Timestamp.valueOf("2026-09-08 08:00:00") else null,
            )
            statement.executeUpdate()
        }
    }

    companion object {
        private const val TABLE_NAME = "schedule_eta_evaluation_history"
    }
}
