package com.noLate.notification.application.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DepartureAlarmReliabilityRunbookContractTest {
    private val runbook = Files.readString(
        Path.of("docs/operations/push-eta-observability-runbook.md"),
    )

    @Test
    fun `canonical gate counts physical native fires and expected visible evidence by platform`() {
        assertThat(runbook).contains(
            "SELECT COUNT(*)",
            "AS native_count",
            "AS visible_count",
            "f.scheduled_for = f.source_trigger_at",
            "f.server_recorded_at < CAST(:as_of_utc AS DATETIME(6))",
            "d.client_presented_at < CAST(:as_of_utc AS DATETIME(6))",
            "native_count = expected_native_count AND visible_count = expected_visible_count",
            "AS expected_channel_evidence_observed",
            "AS expected_channel_evidence_percent",
            "AS observable_native_or_cross_channel_duplicate",
            "`expected_channel_evidence_percent >= 90%`",
            "CONCAT('PLATFORM:', platform)",
            "GROUP BY platform, occurrence_id",
            "platform/occurrence/presentation-mode/semantic-warning slice",
        )
    }

    @Test
    fun `semantic dual presentation and stale trigger blind spot are explicit release gates`() {
        assertThat(runbook).contains(
            "a.semantic_warning_visible = TRUE",
            "expected_native_count",
            "expected_visible_count",
            "orphan_nearby_mismatched_trigger_native_count",
            "WHERE NOT EXISTS",
            "unresolved nearby mismatched-trigger fire",
        )
    }

    @Test
    fun `visible duplicate prevention records durable claim limits and crash windows`() {
        assertThat(runbook).contains(
            "Two OS-visible renders of the same",
            "logical event still produce `visible_count = 1`",
            "foreground-local visible-fallback duplicate prevention",
            "does not cover an FCM notification payload that the OS presents",
            "directly while the app is backgrounded",
            "`(recipient account, logicalEventKey)`",
            "legacy message without `logicalEventKey`",
            "provider message ID as the logical identifier",
            "SHA-256",
            "stable Expo notification identifier",
            "`PENDING` -> OS schedule ->",
            "an explicit scheduling failure rolls the `PENDING` claim back",
            "seven days and capped at 256 records",
            "mismatched or unverified account fails closed",
            "storage fails only after account verification, presentation fails open",
            "claim-before-schedule crash",
            "OS-accepted-before-`COMMITTED` crash",
            "stale-lease recovery can make a bounded re-request",
            "iOS may still re-alert",
            "storage fail-open path can also duplicate",
            "visible-only duplicate rate is `unmeasured`",
            "foreground-local client durable-claim prevention",
        ).doesNotContain(
            "only one path may request the OS presentation",
            "exact_assignment_observed",
            "exact_assignment_percent",
        )
    }
}
