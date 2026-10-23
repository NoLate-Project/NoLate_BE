package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.application.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ServerOwnedWalkingSnapshotTest {
    private fun fixture() = AutomaticJourneyFixture().also { it.useSavedGuidancePlan(mapped = true) }
    private fun rejected(reason: String, block: () -> Unit) {
        assertThatThrownBy(block).isInstanceOf(WalkingContractRejected::class.java).hasMessage(reason)
    }
    @Test fun `both walks bind server-derived tuples without changing saved provenance or native admission`() {
        val f = fixture(); val before = f.mapper.writeValueAsString(f.plan)
        val requests = mutableListOf<ServerWalkingRequest>()
        val value = ServerOwnedWalkingSnapshotFactory(f.mapper, { request -> requests.add(request); syntheticPedestrianBody(request) }, f.clock)
            .issue(f.plan, 7, 2, 1)
        val paths = value.validatedWalks(f.plan, 7, 2, 1, f.clock.instant(), f.mapper)
        assertThat(paths.keys).containsExactly(0, 2)
        assertThat(requests).hasSize(2)
        assertThat(paths.values.map { it.size }).containsExactly(3, 3)
        assertThat(f.mapper.writeValueAsString(f.plan)).isEqualTo(before)
        assertThat(walkingContractFixture("complete")["navigationReady"]).isEqualTo(false)
        (paths[0] as MutableList).clear()
        assertThat(value.validatedWalks(f.plan, 7, 2, 1, f.clock.instant(), f.mapper)[0]).hasSize(3)
    }
    @Test fun `unknown ODsay service can bind walking paths but never becomes operating`() {
        val f = fixture()
        val route = f.plan.route!!.deepCopy<ObjectNode>().also { it.put("transitServiceState", "unknown") }
        val plan = f.plan.copy(route = route)
        val before = f.mapper.writeValueAsString(plan)
        val value = ServerOwnedWalkingSnapshotFactory(f.mapper, ::syntheticBody, f.clock).issue(plan, 7, 2, 1)
        assertThat(value.validatedWalks(plan, 7, 2, 1, f.clock.instant(), f.mapper).keys).containsExactly(0, 2)
        assertThat(f.mapper.writeValueAsString(plan)).isEqualTo(before)
        assertThat(route.path("transitServiceState").asText()).isEqualTo("unknown")
    }
    @Test fun `unowned invalid revision and unsupported provenance reject before provider calls`() {
        val f = fixture(); var calls = 0
        val factory = ServerOwnedWalkingSnapshotFactory(f.mapper, { calls++; error("must not fetch") }, f.clock)
        for ((actor, login, revision) in listOf(Triple(8L, 2L, 1L), Triple(7L, -1L, 1L)))
            rejected("OWNER_CHANGED") { factory.issue(f.plan, actor, login, revision) }
        for (revision in listOf(0L, -1L, 9_007_199_254_740_992L))
            rejected("REVISION_CHANGED") { factory.issue(f.plan, 7, 2, revision) }
        for ((key, changed) in listOf("provider" to "tmap", "source" to "fallback", "routeReliability" to "fallback_estimate",
            "transitServiceState" to "not_operating", "routePlausibility" to "geometry_suspected")) {
            val altered = f.plan.route!!.deepCopy<ObjectNode>(); altered.put(key, changed)
            rejected("UNSUPPORTED_SAVED_ROUTE") { factory.issue(f.plan.copy(route = altered), 7, 2, 1) }
        }
        assertThat(calls).isZero()
    }
    @Test fun `centimetre ODsay rounding connects but a metre-scale gap and competing containers reject`() {
        val f = fixture(); var calls = 0
        val factory = ServerOwnedWalkingSnapshotFactory(f.mapper, { calls++; error("must not fetch") }, f.clock)
        val rounded = f.plan.route!!.deepCopy<ObjectNode>()
        (rounded.path("transitLegs")[0].path("endCoord") as ObjectNode).put("lng", 126.97950001)
        assertThat(ServerOwnedWalkingSnapshotFactory(f.mapper, ::syntheticBody, f.clock)
            .issue(f.plan.copy(route = rounded), 7, 2, 1)
            .validatedWalks(f.plan.copy(route = rounded), 7, 2, 1, f.clock.instant(), f.mapper)).hasSize(2)
        val altered = f.plan.route!!.deepCopy<ObjectNode>()
        (altered.path("transitLegs")[0].path("endCoord") as ObjectNode).put("lng", 126.97955)
        rejected("ENDPOINT_MISMATCH") { factory.issue(f.plan.copy(route = altered), 7, 2, 1) }
        val competing = f.plan.route!!.deepCopy<ObjectNode>(); competing.set<ObjectNode>("routeInfo", competing.deepCopy())
        rejected("UNSUPPORTED_SAVED_ROUTE") { factory.issue(f.plan.copy(route = competing), 7, 2, 1) }
        assertThat(calls).isZero()
    }
    @Test fun `current owner schedule login revision fingerprint and exact expiry are required on reuse`() {
        val f = fixture()
        val value = ServerOwnedWalkingSnapshotFactory(f.mapper, ::syntheticBody, f.clock).issue(f.plan, 7, 2, 1)
        rejected("OWNER_CHANGED") { value.validatedWalks(f.plan, 8, 2, 1, f.clock.instant(), f.mapper) }
        rejected("OWNER_CHANGED") { value.validatedWalks(f.plan, 7, 3, 1, f.clock.instant(), f.mapper) }
        rejected("OWNER_CHANGED") { value.validatedWalks(f.plan.copy(scheduleId = 9002), 7, 2, 1, f.clock.instant(), f.mapper) }
        rejected("REVISION_CHANGED") { value.validatedWalks(f.plan, 7, 2, 2, f.clock.instant(), f.mapper) }
        for (time in listOf(f.clock.instant().minusNanos(1), f.clock.instant().plusSeconds(300)))
            rejected("EXPIRED") { value.validatedWalks(f.plan, 7, 2, 1, time, f.mapper) }
        value.validatedWalks(f.plan, 7, 2, 1, f.clock.instant().plusSeconds(299), f.mapper)
        val altered = f.plan.route!!.deepCopy<ObjectNode>(); altered.put("id", "changed")
        rejected("SAVED_ROUTE_CHANGED") { value.validatedWalks(f.plan.copy(route = altered), 7, 2, 1, f.clock.instant(), f.mapper) }
    }
    @Test fun `saved mutation during fetching never returns a partial snapshot`() {
        val f = fixture()
        val factory = ServerOwnedWalkingSnapshotFactory(f.mapper, { request ->
            (f.plan.route as ObjectNode).put("id", "changed-during-fetch"); syntheticBody(request)
        }, f.clock)
        rejected("SAVED_ROUTE_CHANGED") { factory.issue(f.plan, 7, 2, 1) }
    }
    @Test fun `provider exceptions are sanitized and a failed second walk issues nothing`() {
        val f = fixture(); var calls = 0
        rejected("PROVIDER_UNAVAILABLE") { ServerOwnedWalkingSnapshotFactory(f.mapper, { request ->
            if (++calls == 2) error("private provider URL/body"); syntheticBody(request)
        }, f.clock).issue(f.plan, 7, 2, 1) }
        assertThat(calls).isEqualTo(2)
    }
    @Test fun `deadline and clock rollback reject without fetching the other walk`() {
        val f = fixture()
        for (offset in listOf(-1L, 30L)) {
            var current = f.clock.instant(); var calls = 0
            val clock = object : Clock() {
                override fun getZone() = ZoneOffset.UTC
                override fun withZone(zone: java.time.ZoneId): Clock = this
                override fun instant(): Instant = current
            }
            rejected("FETCH_DEADLINE") { ServerOwnedWalkingSnapshotFactory(f.mapper, { request ->
                calls++; current = current.plusSeconds(offset); syntheticBody(request)
            }, clock).issue(f.plan, 7, 2, 1) }
            assertThat(calls).isEqualTo(1)
        }
    }
    @Test fun `raw body duplicate trailing malformed invalid order coordinate gap limits and endpoints fail closed`() {
        val f = fixture()
        val changes: List<Pair<String, (String) -> String>> = listOf(
            "INVALID_RESPONSE" to { "{" }, "INVALID_RESPONSE" to { "$it {}" },
            "INVALID_RESPONSE" to { it.replace("\"type\":\"FeatureCollection\"", "\"type\":\"FeatureCollection\",\"type\":\"FeatureCollection\"") },
            "FEATURE_ORDER" to { it.replace("\"index\":1", "\"index\":0") },
            "INVALID_COORDINATE" to { it.replace("126.978,37.5665", "null,37.5665") },
            "INPUT_LIMIT" to { " ".repeat(1_048_577) },
            "DISCONNECTED_LINES" to { syntheticPedestrianBody(ServerWalkingRequest(ServerWalkingCoordinate(37.5665, 126.978), ServerWalkingCoordinate(37.5665, 126.9795)), true) },
            "ENDPOINT_MISMATCH" to { syntheticPedestrianBody(ServerWalkingRequest(ServerWalkingCoordinate(37.57, 126.978), ServerWalkingCoordinate(37.57, 126.9795))) },
        )
        for ((reason, change) in changes) rejected(reason) {
            ServerOwnedWalkingSnapshotFactory(f.mapper, { change(syntheticBody(it)) }, f.clock).issue(f.plan, 7, 2, 1)
        }
    }
    @Test fun `isolated scenarios exercise the real contract but never native readiness`() {
        for ((case, reason) in mapOf("owner" to "OWNER_CHANGED", "revision" to "REVISION_CHANGED", "route" to "SAVED_ROUTE_CHANGED",
            "expired" to "EXPIRED", "gap" to "DISCONNECTED_LINES", "source" to "UNSUPPORTED_SAVED_ROUTE")) {
            val result = walkingContractFixture(case)
            assertThat(result["status"]).isEqualTo("BLOCKED")
            assertThat(result["reason"]).isEqualTo(reason)
            assertThat(result["navigationReady"]).isEqualTo(false)
        }
    }
    @Test fun `point and feature limits range segment and empty path failures preserve raw ordering`() {
        val f = fixture()
        fun check(reason: String, change: (ObjectNode) -> Unit) = rejected(reason) {
            ServerOwnedWalkingSnapshotFactory(f.mapper, { request ->
                val root = f.mapper.readTree(syntheticBody(request)) as ObjectNode
                change(root); root.toString()
            }, f.clock).issue(f.plan, 7, 2, 1)
        }
        fun geometry(root: ObjectNode) = root.path("features")[0].path("geometry") as ObjectNode
        check("FEATURE_ORDER") { (it.path("features")[0].path("properties") as ObjectNode).put("index", 9_007_199_254_740_992L) }
        check("INVALID_COORDINATE") { geometry(it).set<com.fasterxml.jackson.databind.JsonNode>("coordinates", f.mapper.readTree("[[126.978,91],[126.9795,37.5665]]")) }
        check("SEGMENT_TOO_LONG") { geometry(it).set<com.fasterxml.jackson.databind.JsonNode>("coordinates", f.mapper.readTree("[[126.978,37.5665],[127,37.5665]]")) }
        check("EMPTY_PATH") { it.set<com.fasterxml.jackson.databind.JsonNode>("features", f.mapper.readTree("""[{"type":"Feature","properties":{"index":0},"geometry":{"type":"Point","coordinates":[126.978,37.5665]}}]""")) }
        check("INVALID_COORDINATE") { geometry(it).put("type", "Point").set<com.fasterxml.jackson.databind.JsonNode>("coordinates", f.mapper.readTree("[126.978,null]")) }
        check("INPUT_LIMIT") {
            geometry(it).set<com.fasterxml.jackson.databind.JsonNode>("coordinates", f.mapper.valueToTree(List(2001) { listOf(126.978, 37.5665) }))
        }
        check("INPUT_LIMIT") {
            val point = f.mapper.readTree("""{"type":"Feature","properties":{"index":0},"geometry":{"type":"Point","coordinates":[126.978,37.5665]}}""")
            it.set<com.fasterxml.jackson.databind.JsonNode>("features", f.mapper.valueToTree(List(4097) { point }))
        }
    }
    private fun syntheticBody(request: ServerWalkingRequest) = syntheticPedestrianBody(request)
}
