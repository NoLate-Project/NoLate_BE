package com.noLate.schedule.domain

import com.noLate.global.config.ProductionSchemaVersionGuard
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class PushEtaTrustMigrationContractTest {
    private val migration = Files.readString(
        Path.of("docs/schedule/migrations/2026-07-31-push-eta-trust.sql"),
    )

    @Test
    fun `partial schema and rerun are rejected before trust DDL`() {
        val precondition = migration.indexOf("CALL assert_push_eta_trust_preconditions()")
        val firstDdl = migration.indexOf("ALTER TABLE schedule_push_job")

        listOf(
            "version = '2026-07-29-departure-alarm-sync-v1'",
            "version = '2026-07-31-push-eta-trust-v1'",
            "table_name = 'schedule_eta_accuracy_observations'",
            "'last_eta_provider_fetched_at', 'last_eta_algorithm_version'",
            "'eta_snapshot_prediction_basis'",
            "'eta_snapshot_provider_id'",
            "'eta_snapshot_target_arrival_at'",
            "'eta_snapshot_algorithm_version'",
            "'eta_observation_exposed_at', 'eta_observation_prompted_at'",
            "'eta_observation_exposed_client_app_version'",
            "'eta_observation_prompted_ux_variant'",
            "'client_ack_recorded_at'",
        ).forEach { guard ->
            assertThat(migration.indexOf(guard))
                .describedAs("precondition: $guard")
                .isGreaterThanOrEqualTo(0)
                .isLessThan(precondition)
        }
        assertThat(precondition).isLessThan(firstDdl)
        assertThat(migration).contains("partial or applied schema requires inspection")
    }

    @Test
    fun `all trust columns indexes and ownership are verified before marker`() {
        val postcondition = migration.indexOf("CALL assert_push_eta_trust_postconditions()")
        val marker = migration.indexOf(
            "INSERT INTO application_schema_migrations(version, description, applied_at)",
        )

        assertThat(migration).contains(
            ") <> 6 OR (",
            ") <> 4 OR (",
            ") <> 23 OR (",
            ") <> 37 THEN",
            "GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'schedule_id,member_id'",
            "GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'eta_source,recorded_at'",
            "'accuracy_eligible,observation_source,precision_seconds,recorded_at'",
            "'algorithm_version,travel_mode,provider_id,prediction_basis,recorded_at'",
            "'backend_cohort_version,client_app_version,algorithm_version,recorded_at'",
            "GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'member_id,id'",
            ") <> 8 OR (",
            "referenced_table_name = 'schedules'",
            "delete_rule = 'CASCADE'",
            "'chk_eta_accuracy_on_time_outcome'",
            "'chk_eta_accuracy_observation_source'",
            "'chk_eta_accuracy_precision_seconds'",
            "'chk_eta_accuracy_observation_shape'",
            "'chk_eta_accuracy_actual_after_departure'",
            "'chk_eta_accuracy_observation_verification'",
            "'chk_eta_accuracy_eligibility_consistency'",
            "'chk_eta_accuracy_eligible_provenance'",
            "prediction_basis = 'PROVIDER_ABSOLUTE'",
            "'chk_eta_accuracy_actual_travel'",
            "'chk_eta_accuracy_report_delay'",
            ") <> 18 THEN",
        )
        assertThat(marker).isGreaterThan(postcondition)
    }

    @Test
    fun `production guard requires the verified trust marker`() {
        assertThat(ProductionSchemaVersionGuard.REQUIRED_SCHEMA_VERSIONS)
            .contains(ProductionSchemaVersionGuard.PUSH_ETA_TRUST_SCHEMA_VERSION)
        assertThat(ProductionSchemaVersionGuard.PUSH_ETA_TRUST_SCHEMA_VERSION)
            .isEqualTo("2026-07-31-push-eta-trust-v1")
    }
}
