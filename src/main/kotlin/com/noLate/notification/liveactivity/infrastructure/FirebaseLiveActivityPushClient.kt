package com.noLate.notification.liveactivity.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.Aps
import com.google.firebase.messaging.ApsAlert
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.noLate.notification.liveactivity.application.ConfirmedLiveActivityDeliveryException
import com.noLate.notification.liveactivity.application.InvalidLiveActivityRouteException
import com.noLate.notification.liveactivity.application.InvalidLiveActivityFcmTokenException
import com.noLate.notification.liveactivity.application.LiveActivityPushClient
import com.noLate.notification.liveactivity.application.LiveActivityPushEvent
import com.noLate.notification.liveactivity.application.LiveActivityPushRequest
import com.noLate.notification.liveactivity.application.LiveActivityPushResult
import com.noLate.notification.liveactivity.application.NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE
import com.noLate.notification.liveactivity.application.RetryableLiveActivityDeliveryException
import java.nio.charset.StandardCharsets

internal class FirebaseLiveActivityPushClient(
    private val firebaseMessaging: FirebaseMessaging,
    private val objectMapper: ObjectMapper,
) : LiveActivityPushClient {
    override fun send(request: LiveActivityPushRequest): LiveActivityPushResult {
        val message = createFirebaseMessage(request)
        return try {
            LiveActivityPushResult(firebaseMessaging.send(message))
        } catch (exception: FirebaseMessagingException) {
            when {
                exception.containsBadDeviceToken() ->
                    // Do not expose either credential and do not retire the normal FCM token here.
                    throw InvalidLiveActivityRouteException(exception)
                exception.messagingErrorCode == MessagingErrorCode.UNREGISTERED ->
                    throw InvalidLiveActivityFcmTokenException(exception)
                exception.messagingErrorCode == MessagingErrorCode.QUOTA_EXCEEDED ->
                    throw RetryableLiveActivityDeliveryException(
                        "Live Activity provider quota rejected the request before acceptance.",
                        exception,
                    )
                exception.messagingErrorCode in CONFIRMED_REJECTION_CODES ->
                    throw ConfirmedLiveActivityDeliveryException(
                        "Live Activity provider request was rejected. code=${exception.messagingErrorCode}",
                        exception,
                    )
                else -> throw exception
            }
        }
    }

    internal fun createFirebaseMessage(request: LiveActivityPushRequest): Message {
        validate(request)
        val eventName = request.event.name.lowercase()
        val apsData = linkedMapOf<String, Any>(
            "timestamp" to request.timestamp,
            "event" to eventName,
            "content-state" to request.contentState,
        )
        request.staleAt?.let { apsData["stale-date"] = it.epochSecond }
        if (request.event == LiveActivityPushEvent.START) {
            apsData["attributes-type"] = requireNotNull(request.attributesType)
            apsData["attributes"] = requireNotNull(request.attributes)
            // iOS 18+ asks the system to deliver the newly-created per-activity update token.
            // Older versions ignore this forward-compatible START-only key.
            apsData["input-push-token"] = 1
        }
        if (request.event == LiveActivityPushEvent.END) {
            apsData["dismissal-date"] = requireNotNull(request.dismissalAt).epochSecond
        }
        val apsBuilder = Aps.builder().putAllCustomData(apsData)
        if (request.alertTitle != null && request.alertBody != null) {
            apsBuilder.setAlert(
                ApsAlert.builder()
                    .setTitle(request.alertTitle)
                    .setBody(request.alertBody)
                    .build()
            )
        }
        return Message.builder()
            .setToken(request.fcmRegistrationToken)
            .setApnsConfig(
                ApnsConfig.builder()
                    .setLiveActivityToken(request.liveActivityToken)
                    .putHeader("apns-push-type", "liveactivity")
                    .putHeader("apns-priority", "10")
                    .putHeader("apns-expiration", request.expirationAt.epochSecond.toString())
                    .setAps(apsBuilder.build())
                    .build()
            )
            .build()
    }

    private fun validate(request: LiveActivityPushRequest) {
        require(request.fcmRegistrationToken.isNotBlank()) { "FCM registration token is required." }
        require(request.liveActivityToken.isNotBlank()) { "Live Activity token is required." }
        require(request.timestamp > 0) { "Live Activity timestamp must be positive." }
        require(request.expirationAt.epochSecond > request.timestamp) {
            "Live Activity expiration must be after its provider timestamp."
        }
        require(request.contentState.isNotEmpty()) { "Live Activity content-state is required." }
        require((request.alertTitle == null) == (request.alertBody == null)) {
            "Live Activity alert title/body must be supplied together."
        }
        when (request.event) {
            LiveActivityPushEvent.START -> {
                require(request.attributesType == NOLATE_LIVE_ACTIVITY_ATTRIBUTES_TYPE) {
                    "Unsupported Live Activity attributes type."
                }
                require(!request.attributes.isNullOrEmpty()) {
                    "Live Activity start attributes are required."
                }
                require(request.alertTitle != null && request.alertBody != null) {
                    "Live Activity remote start alert is required."
                }
                require(request.dismissalAt == null)
            }
            LiveActivityPushEvent.UPDATE -> {
                require(request.attributesType == null && request.attributes == null)
                require(request.alertTitle == null && request.alertBody == null)
                require(request.dismissalAt == null)
            }
            LiveActivityPushEvent.END -> {
                require(request.attributesType == null && request.attributes == null)
                require(request.alertTitle == null && request.alertBody == null)
                require(request.dismissalAt != null)
            }
        }
        val boundedPayload = linkedMapOf<String, Any?>(
            "timestamp" to request.timestamp,
            "event" to request.event.name.lowercase(),
            "content-state" to request.contentState,
            "attributes-type" to request.attributesType,
            "attributes" to request.attributes,
            "input-push-token" to 1.takeIf {
                request.event == LiveActivityPushEvent.START
            },
            "stale-date" to request.staleAt?.epochSecond,
            "dismissal-date" to request.dismissalAt?.epochSecond,
            "alert" to request.alertTitle?.let {
                mapOf("title" to it, "body" to request.alertBody)
            },
        ).filterValues { it != null }
        require(
            objectMapper.writeValueAsString(mapOf("aps" to boundedPayload))
                .toByteArray(StandardCharsets.UTF_8).size <= MAX_ACTIVITYKIT_PAYLOAD_BYTES
        ) {
            "Live Activity payload exceeds the provider size contract."
        }
    }

    private fun FirebaseMessagingException.containsBadDeviceToken(): Boolean =
        generateSequence(this as Throwable?) { it.cause }.any { cause ->
            cause.message?.let {
                it.contains("BadDeviceToken", ignoreCase = true) ||
                    it.contains("BadEnvironmentKeyInToken", ignoreCase = true)
            } == true
        }

    private companion object {
        const val MAX_ACTIVITYKIT_PAYLOAD_BYTES = 4_096
        val CONFIRMED_REJECTION_CODES = setOf(
            MessagingErrorCode.INVALID_ARGUMENT,
            MessagingErrorCode.SENDER_ID_MISMATCH,
            MessagingErrorCode.THIRD_PARTY_AUTH_ERROR,
        )
    }
}
