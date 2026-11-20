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

    @Test fun `reroute columns require their own verified production marker`() {
        val marker = ProductionSchemaVersionGuard.AUTOMATIC_JOURNEY_REROUTE_SCHEMA_VERSION
        assertThat(ProductionSchemaVersionGuard.REQUIRED_SCHEMA_VERSIONS).contains(marker)
        val migration = Files.readString(Path.of("docs/schedule/migrations/2026-11-19-automatic-journey-reroute.sql"))
        assertThat(migration).contains("ADD COLUMN parent_session_id", "uk_auto_journey_parent_session",
            "ADD COLUMN source_route_fingerprint", "ADD COLUMN reroute_snapshot_json",
            "ADD COLUMN reroute_input_json", "ADD COLUMN reroute_bus_admission_json", marker)
        assertThat(migration.indexOf("CALL assert_automatic_journey_reroute_postconditions()"))
            .isLessThan(migration.indexOf("INSERT INTO application_schema_migrations"))
        assertThat(migration).doesNotContain("UPDATE automatic_journey_preparations", "DELETE FROM automatic_journey_preparations")
    }
}
