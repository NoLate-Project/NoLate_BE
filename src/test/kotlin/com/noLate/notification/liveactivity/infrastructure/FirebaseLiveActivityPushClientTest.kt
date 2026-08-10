package com.noLate.notification.liveactivity.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.firebase.messaging.FirebaseMessaging
import com.noLate.notification.liveactivity.application.LiveActivityPushEvent
import com.noLate.notification.liveactivity.application.LiveActivityPushRequest
import com.noLate.notification.liveactivity.application.NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.time.Instant

class FirebaseLiveActivityPushClientTest {
    private val client = FirebaseLiveActivityPushClient(
        mock<FirebaseMessaging>(),
        ObjectMapper().findAndRegisterModules(),
    )

    @Test
    fun `remote start includes required user alert and input token request`() {
        val message = client.createFirebaseMessage(
            LiveActivityPushRequest(
                fcmRegistrationToken = "ordinary-fcm-route",
                liveActivityToken = "a".repeat(64),
                event = LiveActivityPushEvent.START,
                timestamp = 1_785_960_000,
                contentState = contentState(),
                attributesType = NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE,
                attributes = attributes(),
                alertTitle = "출발 준비",
                alertBody = "일정까지 남은 이동 시간을 확인하세요.",
                staleAt = Instant.ofEpochSecond(1_785_960_300),
                expirationAt = Instant.ofEpochSecond(1_785_963_600),
            )
        )

        assertThat(field<Any?>(message, "notification")).isNull()
        assertThat(field<Map<String, String>?>(message, "data")).isNull()
        assertThat(field<Any?>(message, "androidConfig")).isNull()

        val apns = field<Any>(message, "apnsConfig")
        assertThat(field<String>(apns, "liveActivityToken")).isEqualTo("a".repeat(64))
        assertThat(field<Map<String, String>>(apns, "headers")).containsAllEntriesOf(
            mapOf(
                "apns-push-type" to "liveactivity",
                "apns-priority" to "10",
                "apns-expiration" to "1785963600",
            )
        )
        @Suppress("UNCHECKED_CAST")
        val aps = field<Map<String, Any>>(apns, "payload")["aps"] as Map<String, Any?>
        assertThat(aps).containsEntry("event", "start")
        assertThat(aps).containsEntry("attributes-type", NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE)
        assertThat(aps).containsEntry("input-push-token", 1)
        assertThat(aps).containsEntry("content-state", contentState())
        @Suppress("UNCHECKED_CAST")
        val contentState = aps["content-state"] as Map<String, Any?>
        assertThat(contentState).containsEntry("appearance", "dark")
        val alert = requireNotNull(aps["alert"])
        assertThat(field<String>(alert, "title")).isEqualTo("출발 준비")
        assertThat(field<String>(alert, "body")).isEqualTo("일정까지 남은 이동 시간을 확인하세요.")
        assertThat(aps).doesNotContainKeys("sound", "dismissal-date")
    }

    @Test
    fun `end has dismissal date and never repeats immutable attributes`() {
        val message = client.createFirebaseMessage(
            LiveActivityPushRequest(
                fcmRegistrationToken = "ordinary-fcm-route",
                liveActivityToken = "b".repeat(64),
                event = LiveActivityPushEvent.END,
                timestamp = 1_785_960_100,
                contentState = contentState() + ("status" to "arrived"),
                dismissalAt = Instant.ofEpochSecond(1_785_960_100),
                expirationAt = Instant.ofEpochSecond(1_785_963_700),
            )
        )

        val apns = field<Any>(message, "apnsConfig")
        @Suppress("UNCHECKED_CAST")
        val aps = field<Map<String, Any>>(apns, "payload")["aps"] as Map<String, Any?>
        assertThat(aps).containsEntry("event", "end")
        assertThat(aps).containsEntry("dismissal-date", 1_785_960_100L)
        assertThat(aps).doesNotContainKeys(
            "attributes-type",
            "attributes",
            "input-push-token",
            "alert",
            "sound",
        )
    }

    private fun attributes(): Map<String, Any?> = mapOf(
        "scheduleId" to "41",
        "recipientMemberId" to 7L,
        "generation" to 3L,
        "scheduleTitle" to "회의",
        "destinationName" to "강남역",
        "scheduleStartAt" to "2026-08-06T10:00:00Z",
        "actionEventKey" to "key:${"c".repeat(64)}",
        "logicalEventKey" to "key:${"c".repeat(64)}",
    )

    private fun contentState(): Map<String, Any?> = mapOf(
        "revision" to 2L,
        "travelMinutes" to 60,
        "firstWaitMinutes" to 20,
        "recommendedDepartureEpochSeconds" to 1_785_960_000L,
        "updatedAtEpochSeconds" to 1_785_959_900L,
        "actionExpiresAtEpochSeconds" to 1_785_963_600L,
        "status" to "preparing",
        "appearance" to "dark",
        "routeSegments" to listOf(
            mapOf("kind" to "bus", "label" to "버스", "colorHex" to "#2979FF"),
            mapOf("kind" to "destination", "label" to "도착", "colorHex" to "#FF4444"),
        ),
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(instance: Any, name: String): T {
        val declared = generateSequence(instance.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { type -> runCatching { type.getDeclaredField(name) }.getOrNull() }
            .first()
        declared.isAccessible = true
        return declared.get(instance) as T
    }
}
