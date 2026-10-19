package com.noLate.journey.automatic

import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.journey.application.*

/** Separate synthetic plan and provider. Never mutates the running navigation fixture. */
internal fun walkingContractFixture(case: String): Map<String, Any> {
    require(case in setOf("complete", "owner", "revision", "route", "expired", "gap", "source"))
    val fixture = AutomaticJourneyFixture()
    fixture.useSavedGuidancePlan(mapped = true)
    var calls = 0
    val now = fixture.clock.instant()
    val factory = ServerOwnedWalkingSnapshotFactory(fixture.mapper, ServerPedestrianProvider { request ->
        calls++
        syntheticPedestrianBody(request, gap = case == "gap")
    }, fixture.clock)
    return try {
        if (case == "source") (fixture.plan.route as ObjectNode).put("provider", "tmap")
        val snapshot = factory.issue(fixture.plan, if (case == "owner") 8 else 7, 2, 1)
        if (case == "route") (fixture.plan.route as ObjectNode).put("id", "changed-synthetic-route")
        val paths = snapshot.validatedWalks(fixture.plan, 7, 2, if (case == "revision") 2 else 1,
            if (case == "expired") now.plusSeconds(300) else now, fixture.mapper)
        mapOf("fixtureOnly" to true, "case" to case, "status" to "BOUND", "reason" to "TRANSIT_ADMISSION_PENDING",
            "walkCount" to paths.size, "pointCount" to paths.values.sumOf { it.size }, "providerCalls" to calls,
            "navigationReady" to false, "transitProvider" to "odsay", "walkProvider" to "tmap")
    } catch (failure: WalkingContractRejected) {
        mapOf("fixtureOnly" to true, "case" to case, "status" to "BLOCKED", "reason" to failure.reason,
            "walkCount" to 0, "pointCount" to 0, "providerCalls" to calls, "navigationReady" to false,
            "transitProvider" to "odsay", "walkProvider" to "tmap")
    }
}

internal fun syntheticPedestrianBody(request: ServerWalkingRequest, gap: Boolean = false): String {
    val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
    val a = listOf(request.origin.lng, request.origin.lat)
    val b = listOf((request.origin.lng + request.destination.lng) / 2, (request.origin.lat + request.destination.lat) / 2)
    val c = listOf(request.destination.lng, request.destination.lat)
    fun line(index: Int, points: List<List<Double>>) = mapOf("type" to "Feature", "properties" to mapOf("index" to index),
        "geometry" to mapOf("type" to "LineString", "coordinates" to points))
    return mapper.writeValueAsString(mapOf("type" to "FeatureCollection", "features" to listOf(line(0, listOf(a, b)),
        line(1, listOf(if (gap) listOf(b[0] + 0.00001, b[1]) else b, c)))))
}
