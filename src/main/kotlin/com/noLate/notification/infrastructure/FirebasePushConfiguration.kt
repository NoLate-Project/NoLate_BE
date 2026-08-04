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
import com.noLate.notification.application.ConfirmedPushDeliveryException
import com.noLate.notification.application.PushClient
import com.noLate.notification.application.PushPayloadRejectedException
import com.noLate.notification.application.PushSendResult
import com.noLate.notification.domain.OpaquePushIdentifier
import com.noLate.schedule.domain.DEPARTURE_ALARM_SYNC_PAYLOAD_TYPE
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.FileInputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant

private const val ANDROID_CHANNEL_ID = "schedule-push"
private const val SCHEDULE_DEPART_NOW_CATEGORY = "schedule_depart_now"
private const val ETA_EVENT_EXPIRES_AT_KEY = "etaEventExpiresAt"
private val MAX_ANDROID_MESSAGE_TTL_MILLIS = Duration.ofDays(28).toMillis()
/**
 * firebase-admin 9.8.0 `ApiClientUtils.DEFAULT_RETRY_CONFIG` contract.
 *
 * FCM single-send retries HTTP 503 at most four times and accepts Retry-After only up to
 * 60 seconds. Keep these constants in lockstep with the pinned firebase-admin version: the
 * startup invariant below deliberately fails a lease that cannot contain the worst case.
 */
private const val FIREBASE_ADMIN_MAX_RETRIES = 4L
private const val FIREBASE_ADMIN_MAX_RETRY_INTERVAL_MILLIS = 60_000L

@Configuration
@ConditionalOnProperty(prefix = "firebase", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(FirebaseProperties::class)
class FirebasePushConfiguration {

    @Bean
    fun firebasePushTimingInvariant(
        properties: FirebaseProperties,
        @Value("\${notification.push-token.provider-max-call-seconds:60}")
        providerMaxCallSeconds: Long,
        @Value("\${notification.push-token.dispatch-lease-seconds:600}")
        dispatchLeaseSeconds: Long,
        @Value("\${notification.push-token.dispatch-lease-wait-millis:70000}")
        registrationWaitMillis: Long,
    ): FirebasePushTimingInvariant = FirebasePushTimingInvariant.validate(
        connectTimeoutMillis = properties.connectTimeoutMillis,
        readTimeoutMillis = properties.readTimeoutMillis,
        writeTimeoutMillis = properties.writeTimeoutMillis,
        providerMaxCallSeconds = providerMaxCallSeconds,
        dispatchLeaseSeconds = dispatchLeaseSeconds,
        registrationWaitMillis = registrationWaitMillis,
    )

    @Bean
    @Suppress("UNUSED_PARAMETER")
    fun firebaseApp(
        properties: FirebaseProperties,
        firebasePushTimingInvariant: FirebasePushTimingInvariant,
    ): FirebaseApp {
        if (FirebaseApp.getApps().isNotEmpty()) {
            return FirebaseApp.getInstance()
        }

        val credentials = properties.credentialsPath
            ?.takeIf { it.isNotBlank() }
            ?.let { GoogleCredentials.fromStream(FileInputStream(it)) }
            ?: GoogleCredentials.getApplicationDefault()

        val options = FirebaseOptions.builder()
            .setCredentials(credentials)
            .setConnectTimeout(properties.connectTimeoutMillis)
            .setReadTimeout(properties.readTimeoutMillis)
            .setWriteTimeout(properties.writeTimeoutMillis)
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
    fun firebasePushClient(firebaseMessaging: FirebaseMessaging, clock: Clock): PushClient =
        FirebasePushClient(firebaseMessaging, clock)
}

internal class FirebasePushClient(
    private val firebaseMessaging: FirebaseMessaging,
    private val clock: Clock = Clock.systemUTC(),
) : PushClient {
    override fun sendToToken(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>,
    ): PushSendResult {
        val message = createFirebaseMessage(token, title, body, data)
        return try {
            PushSendResult(messageId = firebaseMessaging.send(message))
        } catch (exception: FirebaseMessagingException) {
            when (
                classifyFirebaseFailure(
                    errorCode = exception.messagingErrorCode,
                    badEnvironmentKey = exception.containsBadEnvironmentKeyInToken(),
                )
            ) {
                FirebaseFailureKind.INVALID_TOKEN ->
                    throw InvalidPushTokenException(token, exception)
                FirebaseFailureKind.CONFIRMED_REJECTION ->
                    throw ConfirmedPushDeliveryException(
                        message =
                            "푸시 공급자가 전송을 거절했습니다. code=${exception.messagingErrorCode}",
                        cause = exception,
                    )
                FirebaseFailureKind.UNKNOWN ->
                    // INTERNAL/UNAVAILABLE/코드 없음은 수락 여부가 모호하다.
                    throw exception
            }
        }
    }

    internal fun createFirebaseMessage(
        token: String,
        title: String,
        body: String,
        data: Map<String, String>,
    ): Message {
        if (data["type"] == DEPARTURE_ALARM_SYNC_PAYLOAD_TYPE) {
            return Message.builder()
                .setToken(token)
                // Control-plane sync is intentionally data-only. The client schedules or cancels
                // the native alarm and owns all user-visible presentation.
                .setAndroidConfig(createDataOnlyAndroidConfig())
                .setApnsConfig(createBackgroundApnsConfig())
                .putAllData(data)
                .build()
        }

        val scheduleReminderAction = data.isScheduleDepartureReminder()
        val deliveryControls = createStandardVisibleDeliveryControls(data)
        return Message.builder()
            .setToken(token)
            .setNotification(Notification.builder().setTitle(title).setBody(body).build())
            .setAndroidConfig(createStandardAndroidConfig(deliveryControls))
            .setApnsConfig(
                createStandardApnsConfig(
                    title,
                    body,
                    scheduleReminderAction,
                    deliveryControls,
                )
            )
            .putAllData(data.withNotificationActionCategory(scheduleReminderAction))
            .build()
    }

    private fun createStandardVisibleDeliveryControls(
        data: Map<String, String>,
    ): StandardVisibleDeliveryControls {
        val logicalEventKey = data["logicalEventKey"]?.takeIf(String::isNotBlank)
        val rawExpiresAt = data[ETA_EVENT_EXPIRES_AT_KEY]
            ?: return StandardVisibleDeliveryControls(
                stableIdentifier = logicalEventKey?.let(OpaquePushIdentifier::fingerprint),
            )
        if (logicalEventKey == null) {
            throw PushPayloadRejectedException(
                "ETA push has no stable logical event identity.",
            )
        }
        val expiresAt = runCatching { Instant.parse(rawExpiresAt) }
            .getOrElse {
                throw PushPayloadRejectedException(
                    "ETA push expiration is not a valid Instant.",
                )
            }
        val now = Instant.now(clock)
        if (!expiresAt.isAfter(now)) {
            throw PushPayloadRejectedException(
                "ETA push expired before provider dispatch.",
            )
        }
        val androidTtlMillis = runCatching {
            Duration.between(now, expiresAt).toMillis()
        }.getOrElse {
            throw PushPayloadRejectedException(
                "ETA push expiration exceeds the provider duration range.",
            )
        }
        if (androidTtlMillis <= 0L || androidTtlMillis > MAX_ANDROID_MESSAGE_TTL_MILLIS) {
            throw PushPayloadRejectedException(
                "ETA push expiration is outside the provider TTL range.",
            )
        }
        return StandardVisibleDeliveryControls(
            stableIdentifier = OpaquePushIdentifier.fingerprint(logicalEventKey),
            expiresAt = expiresAt,
            androidTtlMillis = androidTtlMillis,
        )
    }

    private fun createStandardAndroidConfig(
        deliveryControls: StandardVisibleDeliveryControls,
    ): AndroidConfig =
        AndroidConfig.builder()
            .setPriority(AndroidConfig.Priority.HIGH)
            .apply {
                deliveryControls.androidTtlMillis?.let {
                    setTtl(it)
                }
            }
            .setNotification(
                AndroidNotification.builder()
                    .setChannelId(ANDROID_CHANNEL_ID)
                    .setSound("default")
                    .apply {
                        deliveryControls.stableIdentifier?.let {
                            setTag(it)
                        }
                    }
                    .build()
            )
            .build()

    private fun createDataOnlyAndroidConfig(): AndroidConfig =
        AndroidConfig.builder()
            .setPriority(AndroidConfig.Priority.HIGH)
            .build()

    private fun createStandardApnsConfig(
        title: String,
        body: String,
        scheduleReminderAction: Boolean,
        deliveryControls: StandardVisibleDeliveryControls,
    ): ApnsConfig =
        ApnsConfig.builder()
            .putHeader("apns-push-type", "alert")
            .putHeader("apns-priority", "10")
            .apply {
                deliveryControls.stableIdentifier?.let {
                    putHeader("apns-collapse-id", it)
                }
                deliveryControls.expiresAt?.let {
                    putHeader("apns-expiration", it.epochSecond.toString())
                }
            }
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

    private fun createBackgroundApnsConfig(): ApnsConfig =
        ApnsConfig.builder()
            .putHeader("apns-push-type", "background")
            .putHeader("apns-priority", "5")
            .setAps(
                Aps.builder()
                    .setContentAvailable(true)
                    .build()
            )
            .build()
}

private data class StandardVisibleDeliveryControls(
    val stableIdentifier: String? = null,
    val expiresAt: Instant? = null,
    val androidTtlMillis: Long? = null,
)

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

private fun FirebaseMessagingException.containsBadEnvironmentKeyInToken(): Boolean =
    generateSequence(this as Throwable?) { it.cause }
        .any { it.message?.contains("BadEnvironmentKeyInToken") == true }

internal enum class FirebaseFailureKind {
    INVALID_TOKEN,
    CONFIRMED_REJECTION,
    UNKNOWN,
}

internal fun classifyFirebaseFailure(
    errorCode: MessagingErrorCode?,
    badEnvironmentKey: Boolean = false,
): FirebaseFailureKind =
    when {
        errorCode == MessagingErrorCode.UNREGISTERED || badEnvironmentKey ->
            FirebaseFailureKind.INVALID_TOKEN
        errorCode in CONFIRMED_REJECTION_CODES ->
            FirebaseFailureKind.CONFIRMED_REJECTION
        else ->
            FirebaseFailureKind.UNKNOWN
    }

private val CONFIRMED_REJECTION_CODES = setOf(
    MessagingErrorCode.INVALID_ARGUMENT,
    MessagingErrorCode.SENDER_ID_MISMATCH,
    MessagingErrorCode.THIRD_PARTY_AUTH_ERROR,
    MessagingErrorCode.QUOTA_EXCEEDED,
)

@ConfigurationProperties("firebase")
data class FirebaseProperties(
    var enabled: Boolean = false,
    var credentialsPath: String? = null,
    var projectId: String? = null,
    var connectTimeoutMillis: Int = 5_000,
    var readTimeoutMillis: Int = 30_000,
    var writeTimeoutMillis: Int = 5_000,
)

/**
 * Provider I/O and durable token lease must be strictly nested. Equality is unsafe: scheduler
 * jitter or a timeout callback can otherwise outlive the ownership lease and overlap registration.
 *
 * The marker is an explicit dependency of [FirebasePushConfiguration.firebaseApp], so invalid
 * environment values fail before credentials are opened or a provider client is initialized.
 */
data class FirebasePushTimingInvariant private constructor(
    val firebaseTimeoutTotalMillis: Long,
    val firebaseWorstCaseCallMillis: Long,
    val providerMaxCallMillis: Long,
    val dispatchLeaseSeconds: Long,
    val registrationWaitMillis: Long,
) {
    companion object {
        fun validate(
            connectTimeoutMillis: Int,
            readTimeoutMillis: Int,
            writeTimeoutMillis: Int,
            providerMaxCallSeconds: Long,
            dispatchLeaseSeconds: Long,
            registrationWaitMillis: Long,
        ): FirebasePushTimingInvariant {
            require(
                connectTimeoutMillis > 0 &&
                    readTimeoutMillis > 0 &&
                    writeTimeoutMillis > 0
            ) {
                "Firebase connect/read/write timeouts must all be positive."
            }
            require(
                providerMaxCallSeconds > 0 &&
                    providerMaxCallSeconds <= Long.MAX_VALUE / 1_000
            ) {
                "notification.push-token.provider-max-call-seconds must be a positive bounded value."
            }

            val firebaseTimeoutTotalMillis =
                connectTimeoutMillis.toLong() +
                    readTimeoutMillis.toLong() +
                    writeTimeoutMillis.toLong()
            val providerMaxCallMillis = providerMaxCallSeconds * 1_000
            require(
                dispatchLeaseSeconds > 0 &&
                    dispatchLeaseSeconds <= Long.MAX_VALUE / 1_000
            ) {
                "notification.push-token.dispatch-lease-seconds must be a positive bounded value."
            }
            val dispatchLeaseMillis = dispatchLeaseSeconds * 1_000
            val firebaseWorstCaseCallMillis =
                firebaseTimeoutTotalMillis * (FIREBASE_ADMIN_MAX_RETRIES + 1) +
                    FIREBASE_ADMIN_MAX_RETRIES *
                    FIREBASE_ADMIN_MAX_RETRY_INTERVAL_MILLIS

            require(firebaseTimeoutTotalMillis < providerMaxCallMillis) {
                "Firebase connect+read+write timeout must be strictly shorter than " +
                    "notification.push-token.provider-max-call-seconds."
            }
            require(providerMaxCallSeconds < dispatchLeaseSeconds) {
                "notification.push-token.provider-max-call-seconds must be strictly shorter than " +
                    "notification.push-token.dispatch-lease-seconds."
            }
            require(firebaseWorstCaseCallMillis < dispatchLeaseMillis) {
                "Firebase worst-case request/retry budget must be strictly shorter than " +
                    "notification.push-token.dispatch-lease-seconds."
            }
            require(registrationWaitMillis > providerMaxCallMillis) {
                "notification.push-token.dispatch-lease-wait-millis must be strictly longer than " +
                    "notification.push-token.provider-max-call-seconds."
            }

            return FirebasePushTimingInvariant(
                firebaseTimeoutTotalMillis = firebaseTimeoutTotalMillis,
                firebaseWorstCaseCallMillis = firebaseWorstCaseCallMillis,
                providerMaxCallMillis = providerMaxCallMillis,
                dispatchLeaseSeconds = dispatchLeaseSeconds,
                registrationWaitMillis = registrationWaitMillis,
            )
        }
    }
}
