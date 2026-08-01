package com.noLate.notification.domain

import com.noLate.global.config.ProductionSchemaVersionGuard
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class PushDeliveryAckCapabilityMigrationContractTest {

    @Test
    fun `migration fails closed then verifies the ACK capability cohort contract before marker`() {
        val migration = Files.readString(
            Path.of("docs/schedule/migrations/2026-08-01-push-delivery-ack-capability.sql"),
        )
        val normalized = migration.replace(Regex("\\s+"), " ")
        val predecessor = migration.indexOf("2026-08-01-departure-alarm-schedule-receipts-v1")
        val precondition = migration.indexOf("CALL assert_push_ack_capability_preconditions()")
        val firstMutation = migration.indexOf("ALTER TABLE push_device_token")
        val postcondition = migration.indexOf("CALL assert_push_ack_capability_postconditions()")
        val marker = migration.indexOf(
            "INSERT INTO application_schema_migrations(version, description, applied_at)",
        )

        assertTrue(predecessor >= 0)
        assertTrue(precondition > predecessor)
        assertTrue(firstMutation > precondition)
        assertTrue(postcondition > firstMutation)
        assertTrue(marker > postcondition)
        assertTrue(
            normalized.contains(
                "ADD INDEX idx_push_deliveries_reliability_cohort " +
                    "(status, delivered_at, delivery_ack_capability_version, " +
                    "client_received_at)",
            ),
        )
        assertTrue(
            normalized.contains(
                "GROUP_CONCAT(column_name ORDER BY seq_in_index) = " +
                    "'status,delivered_at,delivery_ack_capability_version,client_received_at'",
            ),
        )
        assertTrue(normalized.contains("chk_push_device_token_ack_capability"))
        assertTrue(normalized.contains("chk_push_deliveries_ack_capability"))
        assertTrue(
            normalized.contains(
                "delivery_ack_capability_version IS NOT NULL AND " +
                    "delivery_ack_capability_version <> 1",
            ),
        )
        assertTrue(
            migration.contains(
                ProductionSchemaVersionGuard.PUSH_DELIVERY_ACK_CAPABILITY_SCHEMA_VERSION,
            ),
        )
        assertTrue(
            ProductionSchemaVersionGuard.PUSH_DELIVERY_ACK_CAPABILITY_SCHEMA_VERSION in
                ProductionSchemaVersionGuard.REQUIRED_SCHEMA_VERSIONS,
        )
    }

    @Test
    fun `production rollout requires the follow-up migration and its verified marker`() {
        val rollout = Files.readString(
            Path.of("docs/notification/push-reliability-production-rollout.md"),
        )

        assertTrue(rollout.contains("2026-08-01-push-delivery-ack-capability.sql"))
        assertTrue(
            rollout.contains(
                ProductionSchemaVersionGuard.PUSH_DELIVERY_ACK_CAPABILITY_SCHEMA_VERSION,
            ),
        )
        assertTrue(rollout.contains("idx_push_deliveries_reliability_cohort"))
        assertTrue(rollout.contains("chk_push_device_token_ack_capability"))
        assertTrue(rollout.contains("chk_push_deliveries_ack_capability"))
    }
}
