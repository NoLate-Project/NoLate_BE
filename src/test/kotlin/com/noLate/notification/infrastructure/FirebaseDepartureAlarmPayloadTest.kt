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
    fun `standard push keeps visible notification sound and alert payload`() {
        val message = client.createFirebaseMessage(
            token = "token",
            title = "출발 안내",
            body = "지금 출발하세요",
            data = mapOf(
                "type" to "SCHEDULE_DEPARTURE_REMINDER",
                "scheduleId" to "41",
                "logicalEventKey" to "event:visible-reminder-41",
            ),
        )

        assertThat(field<Any?>(message, "notification")).isNotNull()
        val android = field<Any>(message, "androidConfig")
        assertThat(field<Any?>(android, "notification")).isNotNull()
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
    fun `standard visible retries use the same opaque provider replacement identifier`() {
        val rawEventKey = "event:raw-logical-identity-must-not-leak"
        val first = standardVisibleMessage(rawEventKey)
        val retry = standardVisibleMessage(rawEventKey)
        val distinct = standardVisibleMessage("event:another-logical-identity")

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
        val message = standardVisibleMessage(
            logicalEventKey = "event:eta-expiration-41",
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
    fun `expired or malformed ETA visible payload fails before the provider call`() {
        val messaging = mock<FirebaseMessaging>()
        val rejectingClient = FirebasePushClient(
            messaging,
            Clock.fixed(now, ZoneOffset.UTC),
        )

        val rejectedPayloads = listOf(
            mapOf(
                "logicalEventKey" to "event:expired-eta-41",
                "etaEventExpiresAt" to now.minusMillis(1).toString(),
            ),
            mapOf(
                "logicalEventKey" to "event:boundary-eta-41",
                "etaEventExpiresAt" to now.toString(),
            ),
            mapOf(
                "logicalEventKey" to "event:malformed-eta-41",
                "etaEventExpiresAt" to "not-an-instant",
            ),
            mapOf(
                "etaEventExpiresAt" to now.plusSeconds(120).toString(),
            ),
            mapOf(
                "logicalEventKey" to " ",
                "etaEventExpiresAt" to now.plusSeconds(120).toString(),
            ),
        )
        rejectedPayloads.forEach { rejectedData ->
            assertThatThrownBy {
                rejectingClient.sendToToken(
                    token = "token",
                    title = "출발 안내",
                    body = "지금 출발하세요",
                    data = mapOf("type" to "SCHEDULE_DEPARTURE_REMINDER") + rejectedData,
                )
            }.isExactlyInstanceOf(PushPayloadRejectedException::class.java)
        }

        verifyNoInteractions(messaging)
    }

    private fun standardVisibleMessage(
        logicalEventKey: String,
        expiresAt: Instant? = null,
    ): Message = client.createFirebaseMessage(
        token = "token",
        title = "출발 안내",
        body = "지금 출발하세요",
        data = buildMap {
            put("type", "SCHEDULE_DEPARTURE_REMINDER")
            put("scheduleId", "41")
            put("logicalEventKey", logicalEventKey)
            expiresAt?.let { put("etaEventExpiresAt", it.toString()) }
        },
    )

    private fun replacementIdentifier(message: Message): String {
        val android = field<Any>(message, "androidConfig")
        val notification = field<Any>(android, "notification")
        val androidTag = field<String>(notification, "tag")
        val apns = field<Any>(message, "apnsConfig")
        val apnsCollapseId = field<Map<String, String>>(apns, "headers")["apns-collapse-id"]
        assertThat(apnsCollapseId).isEqualTo(androidTag)
        return androidTag
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(target) as T
        }
}
