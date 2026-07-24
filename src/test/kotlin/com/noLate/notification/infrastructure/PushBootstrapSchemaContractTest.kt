package com.noLate.notification.infrastructure

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Production uses the reviewed v4 migration, while local/bootstrap environments use schema.sql.
 * Keep both representations aligned so a green local context cannot hide a production-only
 * missing column or index.
 */
class PushBootstrapSchemaContractTest {

    @Test
    fun `bootstrap push token table contains the provider lease ownership contract`() {
        val table = tableDefinition("push_device_token")

        listOf(
            "dispatch_lease_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL",
            "dispatch_lease_until DATETIME(6) NULL",
            "retirement_requested BOOLEAN NOT NULL DEFAULT FALSE",
            "INDEX idx_push_device_token_dispatch_lease (dispatch_lease_until, id)",
        ).forEach { required ->
            assertTrue(table.contains(required), "schema.sql is missing: $required")
        }
    }

    @Test
    fun `bootstrap app notification table separates claims from retry budget failures`() {
        val table = tableDefinition("app_notifications")

        assertTrue(
            table.contains(
                "dispatch_failure_count INT NOT NULL DEFAULT 0 " +
                    "COMMENT 'Actual retry-budget failures; expected deferrals do not increment'",
            ),
            "schema.sql must contain app_notifications.dispatch_failure_count",
        )
    }

    @Test
    fun `bootstrap source delivery and history keep typed shared resource identity`() {
        val source = tableDefinition("app_notifications")
        val delivery = tableDefinition("push_deliveries")
        val history = tableDefinition("push_send_history")

        listOf(
            "calendar_id BIGINT NULL COMMENT 'Immutable shared-calendar authorization resource id'",
            "INDEX idx_app_notifications_calendar_id (calendar_id)",
        ).forEach { required ->
            assertTrue(source.contains(required), "app_notifications is missing: $required")
        }
        listOf(
            "calendar_id BIGINT NULL COMMENT 'Frozen shared-calendar authorization resource id'",
            "INDEX idx_push_deliveries_calendar_id (calendar_id)",
        ).forEach { required ->
            assertTrue(delivery.contains(required), "push_deliveries is missing: $required")
        }
        listOf(
            "logical_event_key VARCHAR(100) NULL COMMENT 'Canonical durable outbox/source event key'",
            "category_id BIGINT NULL COMMENT 'Immutable category resource id when applicable'",
            "calendar_id BIGINT NULL COMMENT 'Immutable shared-calendar resource id when applicable'",
            "INDEX idx_push_send_history_member_event (member_id, logical_event_key)",
            "INDEX idx_push_send_history_category_member (category_id, member_id)",
            "INDEX idx_push_send_history_calendar_member (calendar_id, member_id)",
        ).forEach { required ->
            assertTrue(history.contains(required), "push_send_history is missing: $required")
        }
    }

    @Test
    fun `manual migration fails closed for revoked legacy share source and history`() {
        val migration = Files.readString(
            Path.of("docs/notification/2026-07-24-push-delivery-linearization.sql")
        ).replace(Regex("\\s+"), " ")

        listOf(
            "ADD COLUMN calendar_id BIGINT NULL COMMENT 'Immutable shared-calendar authorization resource id'",
            "ADD INDEX idx_app_notifications_calendar_id (calendar_id)",
            "ADD COLUMN calendar_id BIGINT NULL COMMENT 'Frozen shared-calendar authorization resource id'",
            "ADD INDEX idx_push_deliveries_calendar_id (calendar_id)",
            "ALTER TABLE push_send_history ADD COLUMN logical_event_key VARCHAR(100) NULL",
            "ADD INDEX idx_push_send_history_member_event (member_id, logical_event_key)",
            "ADD INDEX idx_push_send_history_category_member (category_id, member_id)",
            "ADD INDEX idx_push_send_history_calendar_member (calendar_id, member_id)",
            "drain revoked legacy category share notifications",
            "drain revoked legacy calendar share notifications",
            "drain revoked legacy category share history",
            "drain revoked legacy calendar share history",
            "revoked category share source remains",
            "revoked calendar share source remains",
            "revoked category share history remains",
            "revoked calendar share history remains",
            "revoked_category_share_histories",
            "revoked_calendar_share_histories",
        ).forEach { required ->
            assertTrue(migration.contains(required), "manual migration is missing: $required")
        }
    }

    private fun tableDefinition(tableName: String): String {
        val schema = Files.readString(Path.of("src/main/resources/schema.sql"))
            .replace(Regex("\\s+"), " ")
        val start = schema.indexOf("CREATE TABLE IF NOT EXISTS $tableName (")
        check(start >= 0) { "schema.sql has no $tableName table" }
        val end = schema.indexOf(") COMMENT=", start)
        check(end > start) { "schema.sql has no complete $tableName table" }
        return schema.substring(start, end)
    }
}
