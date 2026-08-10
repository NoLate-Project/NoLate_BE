package com.noLate.notification.liveactivity.application

import java.time.Instant

const val NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE = "NoLateDepartureAttributes"
const val NOLATE_LIVE_ACTIVITY_SCHEMA_VERSION = 1

interface LiveActivityPushClient {
    fun send(request: LiveActivityPushRequest): LiveActivityPushResult
}

class LiveActivityPushRequest(
    val fcmRegistrationToken: String,
    val liveActivityToken: String,
    val event: LiveActivityPushEvent,
    val timestamp: Long,
    val contentState: Map<String, Any?>,
    val attributesType: String? = null,
    val attributes: Map<String, Any?>? = null,
    val alertTitle: String? = null,
    val alertBody: String? = null,
    val staleAt: Instant? = null,
    val dismissalAt: Instant? = null,
    val expirationAt: Instant,
) {
    override fun toString(): String =
        "LiveActivityPushRequest(event=$event, timestamp=$timestamp, " +
            "contentKeys=${contentState.keys}, hasAttributes=${attributes != null})"
}

enum class LiveActivityPushEvent {
    START,
    UPDATE,
    END,
}

data class LiveActivityPushResult(
    val messageId: String,
)

/** Provider confirmed that this immutable Live Activity request was rejected. */
class ConfirmedLiveActivityDeliveryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The Live Activity route could not be used. It is deliberately separate from the normal FCM
 * token lifecycle: a bad ActivityKit token must never retire a still-valid app registration token.
 */
class InvalidLiveActivityRouteException(
    cause: Throwable? = null,
) : RuntimeException("Live Activity provider route is invalid.", cause)

/** Target app's frozen FCM registration token was confirmed unregistered. */
class InvalidLiveActivityFcmTokenException(
    cause: Throwable? = null,
) : RuntimeException("Live Activity FCM registration route is invalid.", cause)

/** Provider confirmed that no message was accepted and a bounded replay is safe. */
class RetryableLiveActivityDeliveryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
