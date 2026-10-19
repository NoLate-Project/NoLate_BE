package com.noLate.journey.automatic

import com.noLate.journey.application.JourneyActivityCredentials
import com.noLate.journey.application.JourneyActivityDeliveryService
import com.noLate.notification.liveactivity.application.*
import org.springframework.beans.factory.support.StaticListableBeanFactory
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** TEST SOURCE ONLY: actual service/storage, synthetic installation, no Firebase/network client. */
internal class MixedWalkingActivityFixture(val database: WalkingSnapshotDatabaseFixture) {
    val sent = CopyOnWriteArrayList<LiveActivityPushRequest>()
    val client = object : LiveActivityPushClient {
        override fun send(request: LiveActivityPushRequest): LiveActivityPushResult {
            require(request.fcmRegistrationToken == FCM && request.liveActivityToken == TOKEN)
            require(request.event in setOf(LiveActivityPushEvent.UPDATE, LiveActivityPushEvent.END))
            check(sent.size < 100) { "OFFLINE_FIXTURE_BUDGET_EXHAUSTED" }
            sent.add(request)
            return LiveActivityPushResult("offline-fixture-not-apns")
        }
    }
    val delivery = service(client)
    fun service(sender: LiveActivityPushClient = client,
        encryptionKey: String = Base64.getEncoder().encodeToString(ByteArray(32) { 23 })): JourneyActivityDeliveryService {
        val beans = StaticListableBeanFactory().apply { addBean("offlineSender", sender) }
        return JourneyActivityDeliveryService(database.lanes, database.automaticService(), database.fixture.members,
            JourneyActivityCredentials(encryptionKey),
            database.fixture.mapper, database.manager, beans.getBeanProvider(LiveActivityPushClient::class.java),
            FixtureJourneyActivityRouteAuthority(), database.fixture.clock, true)
    }
    fun state(): Map<String, Any> = mapOf("offlineActivityFixture" to true, "guidancePushRealProvider" to false,
        "offlineActivityUpdates" to sent.count { it.event == LiveActivityPushEvent.UPDATE },
        "offlineActivityEnds" to sent.count { it.event == LiveActivityPushEvent.END },
        "offlineActivityRows" to database.tx { database.lanes.count() },
        "offlineActivityEndState" to database.tx { database.lanes.findAll().lastOrNull()?.endState?.name ?: "NONE" })
    companion object {
        const val FCM = "synthetic-offline-installation-not-firebase"
        val TOKEN = "a".repeat(64)
    }
}
