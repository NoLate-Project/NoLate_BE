package com.noLate.notification.infrastructure

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.Aps
import com.google.firebase.messaging.ApsAlert
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import com.noLate.notification.application.InvalidPushTokenException
import com.noLate.notification.application.PushClient
import com.noLate.notification.application.PushSendResult
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.FileInputStream

private const val ANDROID_CHANNEL_ID = "schedule-push"
private const val SCHEDULE_DEPART_NOW_CATEGORY = "schedule_depart_now"

@Configuration
@ConditionalOnProperty(prefix = "firebase", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(FirebaseProperties::class)
class FirebasePushConfiguration {

    @Bean
    fun firebaseApp(properties: FirebaseProperties): FirebaseApp {
        if (FirebaseApp.getApps().isNotEmpty()) {
            return FirebaseApp.getInstance()
        }

        val credentials = properties.credentialsPath
            ?.takeIf { it.isNotBlank() }
            ?.let { GoogleCredentials.fromStream(FileInputStream(it)) }
            ?: GoogleCredentials.getApplicationDefault()

        val options = FirebaseOptions.builder()
            .setCredentials(credentials)
            .apply {
                properties.projectId?.takeIf { it.isNotBlank() }?.let(::setProjectId)
            }
            .build()

        return FirebaseApp.initializeApp(options)
    }

    @Bean
    fun firebaseMessaging(firebaseApp: FirebaseApp): FirebaseMessaging =
        FirebaseMessaging.getInstance(firebaseApp)

    @Bean
    fun firebasePushClient(firebaseMessaging: FirebaseMessaging): PushClient =
        object : PushClient {
            override fun sendToToken(
                token: String,
                title: String,
                body: String,
                data: Map<String, String>,
            ): PushSendResult {
                val scheduleReminderAction = data.isScheduleDepartureReminder()
                val messageData = data.withNotificationActionCategory(scheduleReminderAction)
                val message = Message.builder()
                    .setToken(token)
                    .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                    .setAndroidConfig(createAndroidConfig())
                    .setApnsConfig(createApnsConfig(title, body, scheduleReminderAction))
                    .putAllData(messageData)
                    .build()
                return try {
                    PushSendResult(messageId = firebaseMessaging.send(message))
                } catch (exception: FirebaseMessagingException) {
                    if (exception.isInvalidPushToken()) {
                        throw InvalidPushTokenException(token, exception)
                    }
                    throw exception
                }
            }
        }

    private fun createAndroidConfig(): AndroidConfig =
        AndroidConfig.builder()
            .setPriority(AndroidConfig.Priority.HIGH)
            .setNotification(
                AndroidNotification.builder()
                    .setChannelId(ANDROID_CHANNEL_ID)
                    .setSound("default")
                    .build()
            )
            .build()

    private fun createApnsConfig(title: String, body: String, scheduleReminderAction: Boolean): ApnsConfig =
        ApnsConfig.builder()
            .putHeader("apns-push-type", "alert")
            .putHeader("apns-priority", "10")
            .setAps(
                Aps.builder()
                    .apply {
                        if (scheduleReminderAction) {
                            setCategory(SCHEDULE_DEPART_NOW_CATEGORY)
                        }
                    }
                    .setAlert(
                        ApsAlert.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build()
                    )
                    .setSound("default")
                    .setContentAvailable(true)
                    .build()
            )
            .build()
}

private fun Map<String, String>.isScheduleDepartureReminder(): Boolean =
    this["type"] == "SCHEDULE_DEPARTURE_REMINDER" &&
        this["scheduleId"]?.matches(Regex("[1-9]\\d*")) == true

private fun Map<String, String>.withNotificationActionCategory(
    scheduleReminderAction: Boolean,
): Map<String, String> {
    if (!scheduleReminderAction) return this

    return this + mapOf(
        "categoryId" to SCHEDULE_DEPART_NOW_CATEGORY,
        "categoryIdentifier" to SCHEDULE_DEPART_NOW_CATEGORY,
    )
}

private fun FirebaseMessagingException.isInvalidPushToken(): Boolean =
    messagingErrorCode == MessagingErrorCode.UNREGISTERED ||
        messagingErrorCode == MessagingErrorCode.INVALID_ARGUMENT ||
        containsBadEnvironmentKeyInToken()

private fun FirebaseMessagingException.containsBadEnvironmentKeyInToken(): Boolean =
    generateSequence(this as Throwable?) { it.cause }
        .any { it.message?.contains("BadEnvironmentKeyInToken") == true }

@ConfigurationProperties("firebase")
data class FirebaseProperties(
    var enabled: Boolean = false,
    var credentialsPath: String? = null,
    var projectId: String? = null,
)
