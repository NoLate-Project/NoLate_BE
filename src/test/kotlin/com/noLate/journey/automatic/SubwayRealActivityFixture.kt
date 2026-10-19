package com.noLate.journey.automatic

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.noLate.notification.liveactivity.application.*
import com.noLate.notification.liveactivity.infrastructure.FirebaseLiveActivityPushClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64

/** Explicit test-only sender. Uses the current production Activity API, not the legacy v1-v4 pilot. */
internal class SubwayRealActivityFixture(database: WalkingSnapshotDatabaseFixture,
    sender: LiveActivityPushClient, val installationDigest: String, private val closeClient: () -> Unit = {}) : AutoCloseable {
    private val bounded = BoundedJourneyPushTestClient(sender, installationDigest, 80, 1, 1200)
    private var attempts = 0; private var accepted = 0; private var rejected = 0
    private var ends = 0; private var failure = "NONE"
    private val schemas = linkedMapOf<Int, Int>()
    private val client = object : LiveActivityPushClient {
        @Synchronized override fun send(request: LiveActivityPushRequest): LiveActivityPushResult {
            attempts++
            try {
                val result = bounded.send(request)
                accepted++
                if(request.event == LiveActivityPushEvent.END) ends++
                else (request.contentState["schemaVersion"] as? Number)?.toInt()?.let { schemas[it]=(schemas[it]?:0)+1 }
                return result
            } catch (_: Exception) {
                rejected++; failure="ISOLATED_PROVIDER_SEND_FAILED"
                // Never expose SDK errors, tokens, credential paths, or provider request bodies.
                throw IllegalStateException(failure)
            }
        }
    }
    private val key=ByteArray(32).also { SecureRandom().nextBytes(it) }
    val delivery=MixedWalkingActivityFixture(database).service(client,Base64.getEncoder().encodeToString(key))
    private val database=database
    @Synchronized fun state(): Map<String,Any> = mapOf("realActivityFixture" to true,"guidancePushRealProvider" to true,
        "realActivityAttempts" to attempts,"realActivityAccepted" to accepted,"realActivityRejected" to rejected,
        "realActivityAcceptedBySchema" to schemas.toMap(),"realActivityEnds" to ends,"realActivityFailure" to failure,
        "realActivityRows" to database.tx { database.lanes.count() },
        "realActivityEndState" to database.tx { database.lanes.findAll().lastOrNull()?.endState?.name ?: "NONE" })
    override fun close() { key.fill(0);closeClient() }
    companion object {
        fun fromEnvironment(database: WalkingSnapshotDatabaseFixture): SubwayRealActivityFixture {
            require(System.getenv("NOLATE_JOURNEY_APNS_TEST")=="1") { "ISOLATED_APNS_OPT_IN_REQUIRED" }
            val digest=System.getenv("NOLATE_JOURNEY_TEST_FCM_SHA256").orEmpty()
            require(digest.matches(Regex("[a-f0-9]{64}"))) { "ISOLATED_INSTALLATION_REQUIRED" }
            val project=System.getenv("NOLATE_JOURNEY_TEST_FIREBASE_PROJECT")
            require(project=="nolate-cbdf2") { "ISOLATED_PROJECT_REQUIRED" }
            val path=Path.of(System.getenv("NOLATE_JOURNEY_TEST_FIREBASE_CREDENTIALS").orEmpty())
            require(path.isAbsolute && Files.isRegularFile(path)) { "ISOLATED_CREDENTIALS_REQUIRED" }
            val bytes=Files.newInputStream(path).use { it.readNBytes(65537) }
            require(bytes.size in 1..65536 && database.fixture.mapper.readTree(bytes).path("project_id").asText()==project)
            val options=FirebaseOptions.builder().setProjectId(project)
                .setCredentials(bytes.inputStream().use { GoogleCredentials.fromStream(it) })
                .setConnectTimeout(2000).setReadTimeout(2000).setWriteTimeout(2000).build()
            val app=FirebaseApp.initializeApp(options,"isolated-subway-v8-apns")
            val sender=FirebaseLiveActivityPushClient(FirebaseMessaging.getInstance(app),database.fixture.mapper,
                apnsTopic="com.anonymous.nolatefe.push-type.liveactivity")
            return SubwayRealActivityFixture(database,sender,digest) { app.delete() }
        }
    }
}
