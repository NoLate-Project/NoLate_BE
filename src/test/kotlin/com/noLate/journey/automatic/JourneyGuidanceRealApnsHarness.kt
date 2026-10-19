package com.noLate.journey.automatic

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.noLate.notification.liveactivity.infrastructure.FirebaseLiveActivityPushClient
import java.io.FileInputStream

/** Explicit external test task only. No .env loading, ADC fallback or production Spring context. */
fun main() {
    // A configured environment must be selected by the user; never silently reuse production keys.
    require(System.getenv("NOLATE_JOURNEY_APNS_TEST") == "1") { "Explicit isolated APNs test opt-in is required." }
    val path = System.getenv("NOLATE_JOURNEY_TEST_FIREBASE_CREDENTIALS")?.takeIf { it.isNotBlank() }
        ?: error("Test Firebase credentials configuration is missing.")
    val project = System.getenv("NOLATE_JOURNEY_TEST_FIREBASE_PROJECT")?.takeIf { it.isNotBlank() }
        ?: error("Test Firebase project configuration is missing.")
    val allowedFcmDigest = System.getenv("NOLATE_JOURNEY_TEST_FCM_SHA256")?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        ?: error("A single test installation FCM digest allowlist is required.")
    val options = FirebaseOptions.builder().setCredentials(FileInputStream(path).use { GoogleCredentials.fromStream(it) })
        .setProjectId(project).setConnectTimeout(2000).setReadTimeout(2000).setWriteTimeout(2000).build()
    val app = FirebaseApp.initializeApp(options, "isolated-guidance-apns-test")
    Runtime.getRuntime().addShutdownHook(Thread { app.delete() })
    val client = FirebaseLiveActivityPushClient(FirebaseMessaging.getInstance(app), jacksonObjectMapper().findAndRegisterModules(),
        apnsTopic = "com.anonymous.nolatefe.push-type.liveactivity")
    // Process-local ceiling only: restarting/repeating an external trial still needs a new
    // explicit authorization. Ambiguous provider failures consume the attempt allowance.
    val restricted = BoundedJourneyPushTestClient(client, allowedFcmDigest, 80, 2, 1200)
    authenticatedHarnessMain(restricted)
}
