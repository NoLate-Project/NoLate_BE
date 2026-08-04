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
    fun `canonical gate requires direct native evidence and expected visible evidence by platform`() {
        assertThat(runbook).contains(
            "SELECT COUNT(*)",
            "AS native_count",
            "AS direct_native_count",
            "AS inferred_native_count",
            "AS visible_count",
            "f.scheduled_for = f.source_trigger_at",
            "f.timing_basis IN ('EXACT_CALLBACK', 'OBSERVED_ALERTING')",
            "f.server_recorded_at < CAST(:as_of_utc AS DATETIME(6))",
            "d.client_presented_at < CAST(:as_of_utc AS DATETIME(6))",
            "direct_native_count = expected_native_count",
            "AS expected_channel_evidence_observed",
            "AS expected_channel_evidence_percent",
            "AS observable_distinct_native_identity_or_cross_channel_duplicate",
            "AS deduplicated_native_identity_count",
            "AS direct_native_evidence_count",
            "AS inferred_native_identity_count",
            "SUM(inferred_native_count) AS inferred_native_identity_count",
            "`expected_channel_evidence_percent >= 90%`",
            "CONCAT('PLATFORM:', platform)",
            "GROUP BY platform, occurrence_id",
            "platform/occurrence/presentation-mode/semantic-warning slice",
        )
    }

    @Test
    fun `same identity native duplicates and inferred delivery stay outside the strict gate`() {
        val directNativeSubquery = runbook
            .substringAfter(") AS native_count,")
            .substringBefore(") AS direct_native_count,")
        val inferredNativeSubquery = runbook
            .substringAfter(") AS direct_native_count,")
            .substringBefore(") AS inferred_native_count,")
        assertThat(runbook).contains(
            "`native_count` is not a physical callback count",
            "`(alarmId, generation, scheduledFor)`",
            "same-identity physical native",
            "duplicate rate is `unmeasured`",
            "append-only pre-deduplication fire-attempt",
            "`direct_native_count` admits only `EXACT_CALLBACK` and `OBSERVED_ALERTING`",
            "exact `alarm_generation`",
            "`INFERRED_OS_DELIVERY`",
            "does not",
            "satisfy the 90% expected-channel evidence gate",
            "Only `OBSERVED_ALERTING` is direct iOS presentation evidence",
            "report both same-identity native and visible-only duplicate rates",
            "as `unmeasured`",
            "stale-generation-only exact callback",
            "`native_count = 1`, `direct_native_count = 0`, `inferred_native_count = 0`",
            "`stale_generation_native_count = 1`",
            "`missing_observed`, cannot satisfy",
            "not mislabeled as inferred delivery",
            "explicitly counts",
            "not computed by subtracting",
        ).doesNotContain(
            "fire rows expose physical alarm count",
            "detects missing channel evidence, physical native duplicates",
        )
        assertThat(directNativeSubquery).contains(
            "f.generation = a.alarm_generation",
            "f.timing_basis IN ('EXACT_CALLBACK', 'OBSERVED_ALERTING')",
        )
        assertThat(inferredNativeSubquery).contains(
            "f.timing_basis = 'INFERRED_OS_DELIVERY'",
        ).doesNotContain(
            "f.generation = a.alarm_generation",
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
            "it does not cover an",
            "FCM notification payload that the OS presents",
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
            "ordinary foreground JavaScript/Expo durable-claim prevention",
        ).doesNotContain(
            "only one path may request the OS presentation",
            "exact_assignment_observed",
            "exact_assignment_percent",
        )
    }

    @Test
    fun `Android reminder dual-compatible action contract remains bounded but not exactly once`() {
        assertThat(runbook).contains(
            "All standard visible payloads, including",
            "`SCHEDULE_DEPARTURE_REMINDER`, retain their top-level FCM `Notification`",
            "legacy-client auto-display compatibility",
            "`logicalEventKey` with SHA-256",
            "opaque, stable, 64-ASCII-character provider replacement",
            "`AndroidNotification.tag`",
            "`apns-collapse-id`",
            "ignores a custom Android collapse key",
            "do not treat either",
            "`AndroidConfig.collapseKey`",
            "version-compatible Android action contract",
            "`type`, `scheduleId`, `recipientMemberId`, `logicalEventKey`",
            "`etaEventExpiresAt`",
            "`nolateNotificationTitle`",
            "`nolateNotificationBody`",
            "`nolateNotificationTag`",
            "already-trimmed display text with no ASCII control",
            "1..100-character title",
            "1..500-character body",
            "`key:` followed by 64 lowercase hexadecimal characters",
            "`event:` followed by a canonical UUID",
            "transport tag must equal SHA-256",
            "new Android `FirebaseMessagingService`",
            "overrides the notification-intent handling boundary",
            "before Firebase's base auto-display path",
            "native presentation coordinator",
            "foreground JavaScript handler",
            "native `presentDepartureReminder` bridge",
            "instead of the ordinary JavaScript/Expo durable-claim presenter",
            "process-wide lifecycle lock",
            "native durable claim store",
            "`NotificationCompat` presenter",
            "notification and depart-now action",
            "recipient-bound derivation",
            "does not use",
            "`nolateNotificationTag` directly as the local notification tag",
            "notification id `0` in every app state",
            "stable `(recipient-bound tag, notification id=0)` OS row",
            "same native claim",
            "Ordinary Android and",
            "iOS foreground visible fallbacks continue to use the JavaScript/Expo durable-claim path",
            "legacy Android client without this handler",
            "retained FCM notification",
            "automatically, so deploying",
            "not create a background/quit notification gap",
            "iOS APNs alert",
            "`schedule_depart_now` category",
            "Other normal visible",
            "`DEPARTURE_ALARM_SYNC` remain unchanged",
            "containing `etaEventExpiresAt`",
            "with a nonblank `logicalEventKey`",
            "strictly in the future",
            "millisecond duration to Android TTL",
            "absolute epoch seconds to",
            "`apns-expiration`",
            "confirmed local",
            "rejection before Firebase is",
            "called. The stricter reminder-only identity and display-field checks",
            "return safely to `FAILED`",
            "produce catch-up data instead of sending it late",
            "or vibration again",
            "provider-payload, Android interception/renderer",
            "duplicate rate `unmeasured`",
            "rate `unmeasured`",
        ).doesNotContain(
            "In the foreground the service delegates presentation",
            "matches foreground cross-state deduplication",
        )
    }
}
