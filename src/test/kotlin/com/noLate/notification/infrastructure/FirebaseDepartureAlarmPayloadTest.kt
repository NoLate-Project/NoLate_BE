package com.noLate.notification.infrastructure

import com.google.firebase.messaging.FirebaseMessaging
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class FirebaseDepartureAlarmPayloadTest {
    private val client = FirebasePushClient(mock<FirebaseMessaging>())

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
            "alarmTriggerAt" to "2026-07-29T03:30:00Z",
            "alarmTitle" to "회의",
            "snoozeMinutes" to "5",
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

        val apns = field<Any>(message, "apnsConfig")
        assertThat(field<Map<String, String>>(apns, "headers")).containsAllEntriesOf(
            mapOf(
                "apns-push-type" to "background",
                "apns-priority" to "5",
            )
        )
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
            ),
        )

        assertThat(field<Any?>(message, "notification")).isNotNull()
        val android = field<Any>(message, "androidConfig")
        assertThat(field<Any?>(android, "notification")).isNotNull()
        val apns = field<Any>(message, "apnsConfig")
        assertThat(field<Map<String, String>>(apns, "headers")).containsAllEntriesOf(
            mapOf(
                "apns-push-type" to "alert",
                "apns-priority" to "10",
            )
        )
        @Suppress("UNCHECKED_CAST")
        val aps = field<Map<String, Any>>(apns, "payload")["aps"] as Map<String, Any?>
        assertThat(aps).containsKeys("alert", "sound")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any, name: String): T =
        target.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(target) as T
        }
}
