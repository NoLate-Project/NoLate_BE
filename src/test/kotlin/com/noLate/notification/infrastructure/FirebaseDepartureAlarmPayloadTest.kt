package com.noLate.notification.infrastructure

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.noLate.notification.application.PushPayloadRejectedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class FirebaseDepartureAlarmPayloadTest {
    private val now = Instant.parse("2026-08-04T03:00:00.250Z")
    private val client = FirebasePushClient(
        mock<FirebaseMessaging>(),
        Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `alarm sync is high priority data-only on Android and background-only on APNs`() {
        val data = mapOf(
            "type" to "DEPARTURE_ALARM_SYNC",
            "alarmSchemaVersion" to "1",
            "recipientMemberId" to "7",
            "alarmOperation" to "UPSERT",
            "alarmId" to "schedule:41:member:7",
            "scheduleId" to "41",
            "alarmGeneration" to "0",
            "alarmValidationRevision" to "3",
            "alarmTriggerAt" to "2026-07-29T03:30:00Z",
            "alarmTitle" to "회의",
            "snoozeMinutes" to "5",
            "alarmPlanSchemaVersion" to "2",
            "alarmOccurrencesJson" to "[]",
            "logicalEventKey" to "event:alarm-control-41",
            "etaEventExpiresAt" to "not-an-eta-visible-expiration",
        )

        val message = client.createFirebaseMessage(
            token = "token",
            title = "must not be presented",
            body = "must not be presented",
            data = data,
        )

        assertThat(field<Any?>(message, "notification")).isNull()
        assertThat(field<Map<String, String>>(message, "data")).isEqualTo(data)

        val android = field<Any>(message, "androidConfig")
        assertThat(field<String>(android, "priority")).isEqualTo("high")
        assertThat(field<Any?>(android, "notification")).isNull()
        assertThat(field<Any?>(android, "collapseKey")).isNull()
        assertThat(field<Any?>(android, "ttl")).isNull()

        val apns = field<Any>(message, "apnsConfig")
        val headers = field<Map<String, String>>(apns, "headers")
        assertThat(headers).containsAllEntriesOf(
            mapOf(
                "apns-push-type" to "background",
                "apns-priority" to "5",
            )
        )
        assertThat(headers).doesNotContainKeys("apns-collapse-id", "apns-expiration")
        @Suppress("UNCHECKED_CAST")
        val aps = field<Map<String, Any>>(apns, "payload")["aps"] as Map<String, Any?>
        assertThat(aps["content-available"]).isEqualTo(1)
        assertThat(aps).doesNotContainKeys("alert", "sound")
    }

    @Test
    fun `general visible push keeps top level and Android notification payloads`() {
        val logicalEventKey = "event:general-visible-41"
        val message = client.createFirebaseMessage(
            token = "token",
            title = "공유 안내",
            body = "새 일정이 공유되었습니다.",
            data = mapOf(
                "type" to "SCHEDULE_SHARE_RECEIVED",
                "scheduleId" to "41",
                "logicalEventKey" to logicalEventKey,
            ),
        )

        assertThat(field<Any?>(message, "notification")).isNotNull()
        val android = field<Any>(message, "androidConfig")
        val androidNotification = field<Any>(android, "notification")
        assertThat(field<String>(androidNotification, "tag"))
            .matches("[0-9a-f]{64}")
            .doesNotContain(logicalEventKey)
        assertThat(field<Any?>(android, "ttl")).isNull()
        val apns = field<Any>(message, "apnsConfig")
        val headers = field<Map<String, String>>(apns, "headers")
        assertThat(headers).containsAllEntriesOf(
            mapOf(
                "apns-push-type" to "alert",
                "apns-priority" to "10",
            )
        )
        assertThat(headers).doesNotContainKey("apns-expiration")
        @Suppress("UNCHECKED_CAST")
        val aps = field<Map<String, Any>>(apns, "payload")["aps"] as Map<String, Any?>
        assertThat(aps).containsKeys("alert", "sound")
    }

    @Test
    fun `Android departure reminder keeps legacy auto-display and adds native action data`() {
        val expiresAt = now.plusSeconds(120).plusMillis(500)
        val message = scheduleReminderMessage(
            logicalEventKey = "event:00000000-0000-4000-8000-000000000041",
            expiresAt = expiresAt,
        )

        assertThat(field<Any?>(message, "notification")).isNotNull()
        val android = field<Any>(message, "androidConfig")
        assertThat(field<String>(android, "priority")).isEqualTo("high")
        val androidNotification = field<Any>(android, "notification")
        assertThat(field<Any?>(android, "collapseKey")).isNull()
        assertThat(field<String>(android, "ttl")).isEqualTo("120.500000000s")

        val data = field<Map<String, String>>(message, "data")
        assertThat(data).containsAllEntriesOf(
            mapOf(
                "type" to "SCHEDULE_DEPARTURE_REMINDER",
                "scheduleId" to "41",
                "recipientMemberId" to "7",
                "logicalEventKey" to "event:00000000-0000-4000-8000-000000000041",
                "etaEventExpiresAt" to expiresAt.toString(),
                "nolateNotificationTitle" to "출발 안내",
                "nolateNotificationBody" to "지금 출발하세요",
                "categoryId" to "schedule_depart_now",
                "categoryIdentifier" to "schedule_depart_now",
            )
        )
        assertThat(data["nolateNotificationTag"]).matches("[0-9a-f]{64}")
        assertThat(field<String>(androidNotification, "tag"))
            .isEqualTo(data.getValue("nolateNotificationTag"))
        assertThat(field<String>(androidNotification, "channelId")).isEqualTo("schedule-push")
        assertThat(field<String>(androidNotification, "sound")).isEqualTo("default")

        val apns = field<Any>(message, "apnsConfig")
        val headers = field<Map<String, String>>(apns, "headers")
        assertThat(headers).containsAllEntriesOf(
            mapOf(
                "apns-push-type" to "alert",
                "apns-priority" to "10",
                "apns-collapse-id" to data.getValue("nolateNotificationTag"),
                "apns-expiration" to expiresAt.epochSecond.toString(),
            )
        )
        @Suppress("UNCHECKED_CAST")
        val aps = field<Map<String, Any>>(apns, "payload")["aps"] as Map<String, Any?>
        assertThat(aps).containsKeys("alert", "sound")
        assertThat(aps["category"]).isEqualTo("schedule_depart_now")
    }

    @Test
    fun `departure reminder retries use the same opaque native and APNs replacement identifier`() {
        val rawEventKey = "key:${"a".repeat(64)}"
        val first = scheduleReminderMessage(rawEventKey)
        val retry = scheduleReminderMessage(rawEventKey)
        val distinct = scheduleReminderMessage("key:${"b".repeat(64)}")

        val firstIdentifier = replacementIdentifier(first)
        assertThat(firstIdentifier).isEqualTo(replacementIdentifier(retry))
        assertThat(firstIdentifier).isNotEqualTo(replacementIdentifier(distinct))
        assertThat(firstIdentifier).matches("[0-9a-f]{64}")
        assertThat(firstIdentifier.toByteArray(StandardCharsets.US_ASCII)).hasSize(64)
        assertThat(firstIdentifier).doesNotContain(rawEventKey)

        val apns = field<Any>(first, "apnsConfig")
        assertThat(field<Map<String, String>>(apns, "headers"))
            .containsEntry("apns-collapse-id", firstIdentifier)
    }

    @Test
    fun `ETA visible expiration maps exactly to Android TTL and APNs epoch seconds`() {
        val expiresAt = now.plusSeconds(120).plusMillis(500)
        val message = scheduleReminderMessage(
            logicalEventKey = "event:00000000-0000-4000-8000-000000000042",
            expiresAt = expiresAt,
        )

        val android = field<Any>(message, "androidConfig")
        assertThat(field<String>(android, "ttl")).isEqualTo("120.500000000s")
        val apns = field<Any>(message, "apnsConfig")
        assertThat(field<Map<String, String>>(apns, "headers"))
            .containsEntry("apns-expiration", expiresAt.epochSecond.toString())
        assertThat(field<Map<String, String>>(message, "data"))
            .containsEntry("etaEventExpiresAt", expiresAt.toString())
    }

    @Test
    fun `invalid Android reminder contract fails before the provider call`() {
        val messaging = mock<FirebaseMessaging>()
        val rejectingClient = FirebasePushClient(
            messaging,
            Clock.fixed(now, ZoneOffset.UTC),
        )

        val canonicalData = scheduleReminderData(
            logicalEventKey = "event:00000000-0000-4000-8000-000000000043",
            expiresAt = now.plusSeconds(120),
        )
        val rejectedInputs = listOf(
            RejectedReminderInput(
                data = canonicalData + ("etaEventExpiresAt" to now.minusMillis(1).toString()),
            ),
            RejectedReminderInput(
                data = canonicalData + ("etaEventExpiresAt" to now.toString()),
            ),
            RejectedReminderInput(
                data = canonicalData + ("etaEventExpiresAt" to "not-an-instant"),
            ),
            RejectedReminderInput(data = canonicalData - "etaEventExpiresAt"),
            RejectedReminderInput(data = canonicalData - "logicalEventKey"),
            RejectedReminderInput(data = canonicalData + ("logicalEventKey" to " ")),
            RejectedReminderInput(
                data = canonicalData + ("logicalEventKey" to "event:non-canonical-41"),
            ),
            RejectedReminderInput(
                data = canonicalData + ("logicalEventKey" to "key:${"A".repeat(64)}"),
            ),
            RejectedReminderInput(data = canonicalData - "scheduleId"),
            RejectedReminderInput(data = canonicalData + ("scheduleId" to "0")),
            RejectedReminderInput(data = canonicalData + ("scheduleId" to "1".repeat(201))),
            RejectedReminderInput(data = canonicalData - "recipientMemberId"),
            RejectedReminderInput(data = canonicalData + ("recipientMemberId" to "0")),
            RejectedReminderInput(
                data = canonicalData + ("recipientMemberId" to "9007199254740992"),
            ),
            RejectedReminderInput(data = canonicalData, title = " "),
            RejectedReminderInput(data = canonicalData, title = " 출발 안내"),
            RejectedReminderInput(data = canonicalData, title = "출발\n안내"),
            RejectedReminderInput(data = canonicalData, title = "t".repeat(101)),
            RejectedReminderInput(data = canonicalData, body = " "),
            RejectedReminderInput(data = canonicalData, body = "안내\u007f본문"),
            RejectedReminderInput(data = canonicalData, body = "b".repeat(501)),
        )
        rejectedInputs.forEach { input ->
            assertThatThrownBy {
                rejectingClient.sendToToken(
                    token = "token",
                    title = input.title,
                    body = input.body,
                    data = input.data,
                )
            }.isExactlyInstanceOf(PushPayloadRejectedException::class.java)
        }

        verifyNoInteractions(messaging)
    }

    private fun scheduleReminderMessage(
        logicalEventKey: String,
        expiresAt: Instant = now.plusSeconds(120),
    ): Message = client.createFirebaseMessage(
        token = "token",
        title = "출발 안내",
        body = "지금 출발하세요",
        data = scheduleReminderData(logicalEventKey, expiresAt),
    )

    private fun scheduleReminderData(
        logicalEventKey: String,
        expiresAt: Instant,
    ): Map<String, String> = mapOf(
        "type" to "SCHEDULE_DEPARTURE_REMINDER",
        "scheduleId" to "41",
        "recipientMemberId" to "7",
        "logicalEventKey" to logicalEventKey,
        "etaEventExpiresAt" to expiresAt.toString(),
    )

    private fun replacementIdentifier(message: Message): String {
        val nativeTag = field<Map<String, String>>(message, "data")
            .getValue("nolateNotificationTag")
        val android = field<Any>(message, "androidConfig")
        val androidTag = field<String>(field(android, "notification"), "tag")
        assertThat(androidTag).isEqualTo(nativeTag)
        val apns = field<Any>(message, "apnsConfig")
        val apnsCollapseId = field<Map<String, String>>(apns, "headers")["apns-collapse-id"]
        assertThat(apnsCollapseId).isEqualTo(nativeTag)
        return nativeTag
    }

    private data class RejectedReminderInput(
        val data: Map<String, String>,
        val title: String = "출발 안내",
        val body: String = "지금 출발하세요",
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(target) as T
        }
}
