package com.noLate.journey.automatic

import com.noLate.journey.application.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID

class SavedJourneyWalkingSnapshotTest {
    private fun url() = "jdbc:h2:mem:walking-store-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1"
    @Test fun `new account generation zero is accepted but cannot cross later login`() {
        WalkingSnapshotDatabaseFixture(url()).use { f ->
            f.login = 0
            val value = f.service.issue(7, 9001, 0, 1)
            assertThat(value.loginGeneration).isZero(); assertThat(f.service.current(7, 9001, 0)).isEqualTo(value)
            f.login = 1; assertThatThrownBy { f.service.current(7, 9001, 0) }.hasMessage("OWNER_CHANGED")
        }
    }
    @Test fun `file DB reopen preserves exact id geometry expiry without a new provider fetch`() {
        val directory = Files.createTempDirectory("nolate-walking-unit-")
        val url = "jdbc:h2:file:${directory.resolve("snapshot")};MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE"
        val clock = WalkingSnapshotClock()
        val first = WalkingSnapshotDatabaseFixture(url, clock).use { f -> f.service.issue(7, 9001, 2, 1).also {
            assertThat(f.calls).isEqualTo(2); assertThat(f.tx { f.repository.count() }).isEqualTo(1)
        } }
        WalkingSnapshotDatabaseFixture(url, clock).use { f ->
            assertThat(f.service.current(7, 9001, 2)).isEqualTo(first); assertThat(f.calls).isZero()
            f.service.cancel(7, 9001, 2, first.snapshotId)
            assertThatThrownBy { f.service.current(7, 9001, 2) }.hasMessage("SNAPSHOT_NOT_FOUND")
        }
        // Only this test's exact files are disposable; preserve directory if extra files exist.
        Files.deleteIfExists(directory.resolve("snapshot.mv.db")); Files.deleteIfExists(directory.resolve("snapshot.trace.db"))
        Files.deleteIfExists(directory)
    }
    @Test fun `replacement rotates id keeps one row delayed cancellation cannot erase latest`() {
        WalkingSnapshotDatabaseFixture(url()).use { f ->
            val old = f.service.issue(7, 9001, 2, 1); val latest = f.service.issue(7, 9001, 2, 1)
            assertThat(latest.snapshotId).isNotEqualTo(old.snapshotId)
            assertThatThrownBy { f.service.cancel(7, 9001, 2, old.snapshotId) }.hasMessage("SNAPSHOT_REPLACED")
            assertThat(f.service.current(7, 9001, 2)).isEqualTo(latest); assertThat(f.tx { f.repository.count() }).isEqualTo(1)
            f.service.cancel(7, 9001, 2, latest.snapshotId); f.service.cancel(7, 9001, 2, latest.snapshotId)
            assertThat(f.tx { f.repository.count() }).isZero()
        }
    }
    @Test fun `expiry boundary rollback clock current login visibility and revision deny reads with no refetch`() {
        val clock = WalkingSnapshotClock()
        WalkingSnapshotDatabaseFixture(url(), clock).use { f ->
            val value = f.service.issue(7, 9001, 2, 1)
            f.revision = 2; assertThatThrownBy { f.service.current(7, 9001, 2) }.hasMessage("REVISION_CHANGED"); f.revision = 1
            f.login = 3; assertThatThrownBy { f.service.current(7, 9001, 2) }.hasMessage("OWNER_CHANGED"); f.login = 2
            f.visible = false; assertThatThrownBy { f.service.current(7, 9001, 2) }.hasMessage("OWNER_CHANGED"); f.visible = true
            clock.now = java.time.Instant.ofEpochMilli(value.issuedAtMs - 1)
            assertThatThrownBy { f.service.current(7, 9001, 2) }.hasMessage("EXPIRED")
            clock.now = java.time.Instant.ofEpochMilli(value.expiresAtMs)
            assertThatThrownBy { f.service.current(7, 9001, 2) }.hasMessage("EXPIRED")
            assertThat(f.calls).isEqualTo(2)
            assertThat(f.tx { f.repository.purgeExpired(clock.millis()) }).isEqualTo(1)
            assertThat(f.tx { f.repository.count() }).isZero()
        }
    }
    @Test fun `bad expected revision never fetches or overwrites previous snapshot and route mutation denies current`() {
        WalkingSnapshotDatabaseFixture(url()).use { f ->
            val value = f.service.issue(7, 9001, 2, 1)
            for (revision in listOf(0L, 2L)) assertThatThrownBy { f.service.issue(7, 9001, 2, revision) }.hasMessage("REVISION_CHANGED")
            assertThat(f.calls).isEqualTo(2); assertThat(f.service.current(7, 9001, 2)).isEqualTo(value)
            (f.fixture.plan.route as com.fasterxml.jackson.databind.node.ObjectNode).put("unexpected", true)
            assertThatThrownBy { f.service.current(7, 9001, 2) }.hasMessage("SAVED_ROUTE_CHANGED")
        }
    }
    @Test fun `corrupt persisted JSON and row metadata never returns path or leaks parser cause`() {
        WalkingSnapshotDatabaseFixture(url()).use { f ->
            f.service.issue(7, 9001, 2, 1)
            f.tx { f.repository.findOwnedForUpdate(7, 9001)!!.contractJson = "{private-corrupt-body" }
            assertThatThrownBy { f.service.current(7, 9001, 2) }.hasMessage("STORED_SNAPSHOT_INVALID").hasNoCause()
        }
    }
    @Test fun `cleanup owner and owned schedule removes persisted planned coordinates`() {
        WalkingSnapshotDatabaseFixture(url()).use { f ->
            f.service.issue(7, 9001, 2, 1); f.tx { f.repository.deleteAllByMemberId(7) }
            assertThat(f.tx { f.repository.count() }).isZero()
            f.service.issue(7, 9001, 2, 1); f.tx { f.repository.deleteAllByScheduleIdIn(listOf(9001)) }
            assertThat(f.tx { f.repository.count() }).isZero()
        }
    }
    @Test fun `provider failure or post fetch edit never persists a partial pair`() {
        lateinit var f: WalkingSnapshotDatabaseFixture
        f = WalkingSnapshotDatabaseFixture(url(), provider = ServerPedestrianProvider { request ->
            f.revision = 2; syntheticPedestrianBody(request)
        })
        f.use {
            assertThatThrownBy { f.service.issue(7, 9001, 2, 1) }.hasMessage("REVISION_CHANGED")
            assertThat(f.tx { f.repository.count() }).isZero()
        }
        WalkingSnapshotDatabaseFixture(url(), provider = ServerPedestrianProvider { throw IllegalStateException("private") }).use { failed ->
            assertThatThrownBy { failed.service.issue(7, 9001, 2, 1) }.hasMessage("PROVIDER_UNAVAILABLE")
            assertThat(failed.tx { failed.repository.count() }).isZero()
        }
    }
    @Test fun `codec rejects duplicate unknown altered type TTL and disconnected paths`() {
        val f = AutomaticJourneyFixture().also { it.useSavedGuidancePlan(mapped = true) }
        val codec = WalkingSnapshotCodec(f.mapper)
        val snapshot = ServerOwnedWalkingSnapshotFactory(f.mapper, ServerPedestrianProvider(::syntheticPedestrianBody), f.clock).issue(f.plan, 7, 2, 1)
        val json = codec.encode(snapshot)
        assertThat(codec.encode(codec.decode(json))).isEqualTo(json)
        for (bad in listOf(json.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            json.replace("\"schemaVersion\":1", "\"schemaVersion\":18446744073709551617"),
            json.replace("\"legIndex\":0", "\"legIndex\":18446744073709551616"),
            json.replaceFirst("{", "{\"schemaVersion\":1,"), json.dropLast(1)+",\"extra\":true}", json+"{}", " ".repeat(524289)))
            assertThatThrownBy { codec.decode(bad) }.hasMessage("STORED_SNAPSHOT_INVALID").hasNoCause()
        val altered = f.mapper.readTree(json) as com.fasterxml.jackson.databind.node.ObjectNode
        altered.put("expiresAt", snapshot.expiresAt.plusSeconds(1).toString())
        assertThatThrownBy { codec.decode(altered.toString()) }.hasMessage("STORED_SNAPSHOT_INVALID")
        altered.put("expiresAt", snapshot.expiresAt.toString())
        (altered.path("walks")[0].path("points")[1] as com.fasterxml.jackson.databind.node.ObjectNode).put("lat", 0)
        assertThatThrownBy { codec.decode(altered.toString()) }.hasMessage("STORED_SNAPSHOT_INVALID")
    }
}
