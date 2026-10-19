package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.application.*
import com.noLate.journey.domain.*
import com.noLate.notification.liveactivity.application.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.springframework.beans.factory.support.StaticListableBeanFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real MySQL CLI avoids Docker 29/Testcontainers API negotiation; no app profile or user DB. */
@Tag("mysql")
class JourneyActivityMySqlTest {
    @Test fun `reviewed migrations validate entities and durable END is claimed once after restart on MySQL`() {
        assumeTrue(runCatching { command(listOf("docker", "info", "--format", "{{.ServerVersion}}")).first }.getOrDefault(false),
            "Docker is required; a skip is not a MySQL pass")
        val name = "nolate-journey-mysql-${UUID.randomUUID()}"
        val started = command(listOf("docker", "run", "--detach", "--rm", "--name", name,
            "--env", "MYSQL_ROOT_PASSWORD=isolated-root", "--env", "MYSQL_DATABASE=journey_test",
            "--env", "MYSQL_USER=journey", "--env", "MYSQL_PASSWORD=isolated", "-p", "127.0.0.1::3306", "mysql:8.4"))
        check(started.first) { "Isolated MySQL startup failed" }
        fun sql(value: String) = command(listOf("docker", "exec", "-i", name, "mysql", "--batch", "--skip-column-names",
            "-ujourney", "-pisolated", "journey_test"), value)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (!sql("SELECT 1;").first) { check(System.nanoTime() < deadline) { "Isolated MySQL readiness timeout" }; Thread.sleep(500) }
            check(sql("CREATE TABLE application_schema_migrations(version VARCHAR(128) PRIMARY KEY, description VARCHAR(255) NOT NULL, applied_at DATETIME(6) NOT NULL);").first)
            val predecessor = Files.readString(Path.of("docs/schedule/migrations/2026-10-04-automatic-journey-preparations.sql"))
            val migration = Files.readString(Path.of("docs/schedule/migrations/2026-10-06-journey-activity-lanes.sql"))
            assertThat(sql(predecessor).first).isTrue()
            val applied = sql(migration); assertThat(applied.first).withFailMessage(applied.second.take(1000)).isTrue()
            assertThat(sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='journey_activity_lanes';").second.trim()).endsWith("25")
            assertThat(sql(migration).first).isFalse() // Partial/repeated migration fails closed.
            assertThat(sql(Files.readString(Path.of("docs/schedule/migrations/2026-10-12-journey-walking-snapshots.sql"))).first).isTrue()
            assertThat(sql(Files.readString(Path.of("docs/schedule/migrations/2026-10-12-mixed-walking-binding.sql"))).first).isTrue()
            val port = command(listOf("docker", "port", name, "3306/tcp")).second.trim().substringAfterLast(':').toInt()
            val url = "jdbc:mysql://127.0.0.1:$port/journey_test?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC"
            fun database() = DurableJourneyFixtureDatabase(url, "journey", "isolated", "validate", "org.hibernate.dialect.MySQLDialect")
            val clock = AutomaticJourneyMutableClock()
            val sent = ConcurrentLinkedQueue<LiveActivityPushRequest>()
            val sender = object : LiveActivityPushClient {
                override fun send(request: LiveActivityPushRequest): LiveActivityPushResult {
                    sent.add(request); return LiveActivityPushResult("synthetic-mysql")
                }
            }
            fun service(db: DurableJourneyFixtureDatabase, f: AutomaticJourneyFixture): JourneyActivityDeliveryService {
                val beans = StaticListableBeanFactory(); beans.addBean("syntheticSender", sender)
                return JourneyActivityDeliveryService(db.lanes, f.service(), f.members,
                    JourneyActivityCredentials(Base64.getEncoder().encodeToString(ByteArray(32) { 17 })), f.mapper,
                    db.manager, beans.getBeanProvider(LiveActivityPushClient::class.java), FixtureJourneyActivityRouteAuthority(), clock, true)
            }
            var db = database()
            val original = AutomaticJourneyFixture(clock, 600, db)
            original.service().prepare(7, 9001, 2, original.prepareCommand())
            original.service().departure(7, 9001, 2, original.sessionId, original.event())
            val now = clock.instant().epochSecond
            val body = original.mapper.readTree("""{"generation":1,"routeRevision":1,"clientOwnerId":"${original.owner}","routeInputDigest":"${original.digest}","activityId":"${original.sessionId}","fcmRegistrationToken":"synthetic-mysql-fcm","liveActivityToken":"${"a".repeat(64)}","timestamp":$now,"contentState":{"schemaVersion":1,"sessionId":"${original.sessionId}","scheduleId":"9001","generation":1,"routeRevision":1,"routeInputDigest":"${original.digest}","sequence":1,"status":"ACTIVE","phase":"WALKING_TO_STOP","quality":"GOOD","instruction":"승차 정류장으로 이동 중","activeLegIndex":0,"walkProgress":0.3,"observedAt":$now,"validUntil":${now + 15},"boardingConfirmed":false,"arrivalStatus":"UNAVAILABLE","arrival":null}}""") as ObjectNode
            service(db, original).update(7, 9001, 2, original.sessionId, body)
            db.close()
            db = database()
            try {
                val restored = AutomaticJourneyFixture(clock, 600, db)
                assertThat(restored.state()["lastState"]).isEqualTo("ACTIVE")
                clock.now = clock.now.plusSeconds(601)
                db.tx { AutomaticJourneyExpiry(db.preparations, clock, true, true, true).expireElapsedSessions() }
                val one = service(db, restored); val two = service(db, restored)
                val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
                try {
                    val futures = listOf(one, two).map { worker -> pool.submit<Int> { start.await(); worker.dispatchTerminal() } }
                    start.countDown(); assertThat(futures.sumOf { it.get(20, TimeUnit.SECONDS) }).isEqualTo(1)
                } finally { pool.shutdownNow() }
                assertThat(sent.map { it.event }).containsExactly(LiveActivityPushEvent.UPDATE, LiveActivityPushEvent.END)
                assertThat(db.tx { db.lanes.findBySessionId(original.sessionId)!!.endState }).isEqualTo(JourneyActivityEndState.ACCEPTED)
                assertThat(one.dispatchTerminal()).isZero()
                assertThat(restored.departureCount.get()).isZero()
            } finally { db.close() }
            database().use { finalDb ->
                val restored = AutomaticJourneyFixture(clock, 600, finalDb)
                assertThat(service(finalDb, restored).dispatchTerminal()).isZero()
                assertThat(finalDb.tx { finalDb.lanes.countByEndState(JourneyActivityEndState.ACCEPTED) }).isEqualTo(1)
            }
        } finally {
            // Only the UUID-named disposable container created above, never existing/user containers.
            check(command(listOf("docker", "rm", "--force", name)).first) { "Isolated MySQL cleanup failed" }
        }
    }

    private fun command(args: List<String>, input: String = ""): Pair<Boolean, String> {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        process.outputStream.use { it.write(input.toByteArray()) }
        val output = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { output.append(it).append('\n') } } }
        reader.start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) { process.destroyForcibly(); reader.join(1000); error("Isolated command timeout") }
        reader.join(1000); return (process.exitValue() == 0) to output.toString()
    }
}
