package com.noLate.schedule.application.cache

import com.noLate.global.config.ProductionSchemaVersionGuard
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ScheduleCalendarCacheRevisionMigrationContractTest {
    @Test
    fun `reviewed migration creates and backfills independent revision rows before marker`() {
        val migration = Files.readString(
            Path.of(
                "docs/schedule/migrations/" +
                    "2026-07-27-schedule-calendar-cache-revision.sql"
            ),
        )
        val createTable = migration.indexOf(
            "CREATE TABLE schedule_calendar_cache_revisions",
        )
        val backfill = migration.indexOf(
            "INSERT INTO schedule_calendar_cache_revisions(member_id, revision)",
        )
        val postcondition = migration.indexOf(
            "CALL assert_schedule_cache_revision_postconditions()",
        )
        val marker = migration.indexOf(
            "INSERT INTO application_schema_migrations(version, description, applied_at)",
        )

        assertTrue(migration.contains(ProductionSchemaVersionGuard.PUSH_RELIABILITY_SCHEMA_VERSION))
        assertTrue(
            migration.contains(
                ProductionSchemaVersionGuard.APPLE_TOKEN_LIFECYCLE_SCHEMA_VERSION,
            )
        )
        assertTrue(migration.contains(ProductionSchemaVersionGuard.ACCOUNT_DELETION_SCHEMA_VERSION))
        assertTrue(
            migration.contains(
                ProductionSchemaVersionGuard.SCHEDULE_CALENDAR_CACHE_REVISION_SCHEMA_VERSION,
            )
        )
        assertTrue(createTable >= 0)
        assertTrue(backfill > createTable)
        assertTrue(postcondition > backfill)
        assertTrue(marker > postcondition)
        assertTrue(migration.contains("WHERE revision < 0"))
        assertTrue(migration.contains("Mixed old Redis-revision writers"))
        assertTrue(migration.contains("LEFT JOIN schedule_calendar_cache_revisions"))
        assertTrue(!migration.contains("FOREIGN KEY"))
    }

    @Test
    fun `bootstrap schema creates the FK-free revision table contract`() {
        val schema = Files.readString(Path.of("src/main/resources/schema.sql"))

        assertTrue(
            schema.contains("CREATE TABLE IF NOT EXISTS schedule_calendar_cache_revisions"),
        )
        assertTrue(schema.contains("revision BIGINT NOT NULL DEFAULT 0"))
        assertTrue(!schema.substringAfter("CREATE TABLE IF NOT EXISTS schedule_calendar_cache_revisions")
            .substringBefore(";")
            .contains("FOREIGN KEY"))
        assertTrue(
            schema.contains(
                "docs/schedule/migrations/2026-07-27-schedule-calendar-cache-revision.sql",
            )
        )
    }
}
