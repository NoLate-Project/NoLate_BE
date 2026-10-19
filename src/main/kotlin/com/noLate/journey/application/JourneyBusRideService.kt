package com.noLate.journey.application

import com.noLate.journey.domain.JourneyBusBindingCommand
import com.noLate.transit.domain.SeoulBusVehicleQuery
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock

/** Opt-in, on-demand sidecar. No GPS upload, background scheduler, or boarding decision. */
@Service
class JourneyBusRideService(
    private val automatic: AutomaticJourneyService,
    private val provider: SeoulTransitArrivalClient,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${journey.bus-ride.enabled:false}") private val enabled: Boolean = false,
) {
    private class Entry(val mapping: JourneyBusRideMapping.Result, val untilNanos: Long)
    private val mappings = linkedMapOf<String, Entry>()
    private val nextVehicleAt = linkedMapOf<String, Long>()
    private val monitor = Any()

    fun binding(member: Long, schedule: Long, login: Long, session: String, command: JourneyBusBindingCommand): Map<String, Any> {
        val route = automatic.requireObservationFence(member, schedule, login, session, command)
        val mapping = mapping(route,command.selectedBusLegIndex())
        checkAfter(member, schedule, login, session, command, route)
        val verified = mapping.verified
        return envelope(session, command) + mapOf("status" to mapping.status) + if (verified == null) emptyMap() else mapOf(
            "routeId" to verified.routeId, "direction" to verified.direction, "coordinates" to verified.coordinates,
            "departureWindowMeters" to verified.departureWindowMeters,
            "validUntil" to minOf(clock.instant().plusSeconds(30), automatic.validate(member, schedule, login, session,
                com.noLate.journey.domain.AutomaticJourneyStopCommand(command.generation, command.routeRevision, command.clientOwnerId, command.routeInputDigest)).validUntil).toEpochMilli() / 1000.0)
    }

    fun observe(member: Long, schedule: Long, login: Long, session: String, command: JourneyBusBindingCommand): Map<String, Any> {
        val route = automatic.requireObservationFence(member, schedule, login, session, command)
        val mapping = mapping(route,command.selectedBusLegIndex())
        val verified = mapping.verified
        val result: Map<String, Any> = when {
            verified == null -> mapOf("status" to mapping.status, "vehicles" to emptyList<Any>())
            !takeVehicleBudget(verified.routeId) -> mapOf("status" to "POLL_BUDGET_EXHAUSTED", "vehicles" to emptyList<Any>())
            else -> try {
                val batch = provider.getBusVehicleObservations(SeoulBusVehicleQuery(verified.routeId,
                    verified.stops.first().sequence, verified.observationEndSequence))
                val vehicles = JourneyBusRideMapping.vehicles(verified, batch, clock.instant())
                mapOf("status" to if (vehicles.isEmpty()) "VEHICLE_EVIDENCE_UNAVAILABLE" else "AVAILABLE", "vehicles" to vehicles)
            } catch (_: Exception) { mapOf("status" to "VEHICLE_PROVIDER_UNAVAILABLE", "vehicles" to emptyList<Any>()) }
        }
        checkAfter(member, schedule, login, session, command, route)
        return envelope(session, command) + result
    }
    private fun checkAfter(member: Long, schedule: Long, login: Long, session: String, command: JourneyBusBindingCommand, before: JourneyRouteSnapshot) {
        val after = automatic.requireObservationFence(member, schedule, login, session, command)
        if (after.fingerprint != before.fingerprint) throw com.noLate.global.error.BusinessException(com.noLate.global.error.ErrorCode.INVALID_STATE)
    }
    private fun mapping(route: JourneyRouteSnapshot, index: Int): JourneyBusRideMapping.Result = synchronized(monitor) {
        if (!enabled) return@synchronized JourneyBusRideMapping.Result("RIDE_ADAPTER_DISABLED")
        val now = System.nanoTime()
        mappings.entries.removeIf { now >= it.value.untilNanos }
        val key = "${route.fingerprint}:$index"
        mappings[key]?.let { return@synchronized it.mapping }
        val saved = JourneyBusRouteBindingResolver().resolve(route,index)
        val id = saved.query?.providerRouteId
        if (id == null || !id.matches(Regex("[1-9][0-9]{8}"))) return@synchronized JourneyBusRideMapping.Result("ROUTE_NAMESPACE_UNAVAILABLE")
        if (mappings.size >= 32) return@synchronized JourneyBusRideMapping.Result("MAPPING_BUDGET_EXHAUSTED")
        val result = try {
            val stops = provider.getBusRouteDiagnosticStops(id)
            if (stops.isEmpty()) JourneyBusRideMapping.Result("CATALOG_UNAVAILABLE")
            else JourneyBusRideMapping.resolve(route, stops, provider.getBusRoutePath(id),index)
        } catch (_: Exception) { JourneyBusRideMapping.Result("CATALOG_UNAVAILABLE") }
        mappings[key] = Entry(result, System.nanoTime() + 300_000_000_000L)
        result
    }
    private fun takeVehicleBudget(route: String): Boolean = synchronized(monitor) {
        val now = System.nanoTime()
        nextVehicleAt.entries.removeIf { now >= it.value }
        if (nextVehicleAt.containsKey(route) || nextVehicleAt.size >= 32) return@synchronized false
        nextVehicleAt[route] = now + 10_000_000_000L
        true
    }
    private fun envelope(session: String, c: JourneyBusBindingCommand): Map<String, Any> = mapOf(
        "schemaVersion" to if (c.busLegIndex == null) 2 else 3, "sessionId" to session, "generation" to c.generation,
        "routeRevision" to c.routeRevision, "routeInputDigest" to c.routeInputDigest,
        "identityEvidence" to "OFFICIAL_STOP_SHAPE_CROSSWALK") +
        (c.busLegIndex?.let { mapOf("busLegIndex" to it) } ?: emptyMap())
}
