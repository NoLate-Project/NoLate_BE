package com.noLate.notification.infrastructure

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
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
                val message = Message.builder()
                    .setToken(token)
                    .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                    .putAllData(data)
                    .build()
                return try {
                    PushSendResult(messageId = firebaseMessaging.send(message))
                } catch (exception: FirebaseMessagingException) {
                    if (
                        exception.messagingErrorCode == MessagingErrorCode.UNREGISTERED ||
                        exception.messagingErrorCode == MessagingErrorCode.INVALID_ARGUMENT
                    ) {
                        throw InvalidPushTokenException(token, exception)
                    }
                    throw exception
                }
            }
        }
}

@ConfigurationProperties("firebase")
data class FirebaseProperties(
    var enabled: Boolean = false,
    var credentialsPath: String? = null,
    var projectId: String? = null,
)
