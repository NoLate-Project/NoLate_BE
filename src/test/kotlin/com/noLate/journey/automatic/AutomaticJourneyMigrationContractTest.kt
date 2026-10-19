package com.noLate.journey.automatic

import com.noLate.global.config.ProductionSchemaVersionGuard
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AutomaticJourneyMigrationContractTest {
    @Test fun `new table requires an explicit schema marker even when runtime gates are off`() {
        val marker = ProductionSchemaVersionGuard.AUTOMATIC_JOURNEY_SCHEMA_VERSION
        assertThat(ProductionSchemaVersionGuard.REQUIRED_SCHEMA_VERSIONS).contains(marker)
        val migration = Files.readString(Path.of("docs/schedule/migrations/2026-10-04-automatic-journey-preparations.sql"))
        assertThat(migration).contains("CREATE TABLE automatic_journey_preparations", "uk_auto_journey_generation", "retention_expires_at", marker)
        assertThat(migration.indexOf("CALL assert_automatic_journey_postconditions()")).isLessThan(migration.indexOf("INSERT INTO application_schema_migrations"))
        assertThat(migration).doesNotContain("ALTER TABLE journey_guidance_sessions", "route_snapshot_json", "latitude", "longitude")
    }
}
