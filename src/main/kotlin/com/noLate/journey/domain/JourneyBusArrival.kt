package com.noLate.journey.domain

import java.time.Instant

/** Explicit arrival-provider sidecar. This does not certify a routing-provider ID crosswalk. */
data class JourneyBusArrivalQuery(
    val namespace: String,
    val arsId: String,
    val providerRouteId: String,
    val directionLabel: String,
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = JourneyBusLegIndexDeserializer::class)
    val legIndex: Int,
) {
    fun valid() = namespace == "SEOUL_BUS" && arsId.matches(Regex("[0-9]{5}")) && arsId != "00000" &&
        providerRouteId.matches(Regex("[1-9][0-9]{0,19}")) && legIndex in 1..29 && legIndex % 2 == 1 &&
        directionLabel.isNotBlank() && directionLabel.length <= 128 && directionLabel.none(Char::isISOControl)
}

data class JourneyBusArrivalCommand(
    val generation: Long,
    val routeRevision: Long,
    val clientOwnerId: String,
    val routeInputDigest: String,
    val query: JourneyBusArrivalQuery,
) {
    fun fence() = JourneyBusBindingCommand(generation, routeRevision, clientOwnerId, routeInputDigest, query.legIndex)
}

data class JourneyBusBindingCommand(val generation: Long, val routeRevision: Long, val clientOwnerId: String, val routeInputDigest: String,
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = JourneyBusLegIndexDeserializer::class)
    val busLegIndex: Int? = null) {
    fun selectedBusLegIndex() = busLegIndex ?: 1
}

/** An explicit occurrence index is never a Boolean, decimal, string or a coerced array offset. */
class JourneyBusLegIndexDeserializer : com.fasterxml.jackson.databind.JsonDeserializer<Int>() {
    override fun deserialize(parser: com.fasterxml.jackson.core.JsonParser, context: com.fasterxml.jackson.databind.DeserializationContext): Int {
        if (parser.currentToken != com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT ||
            !parser.bigIntegerValue.let { it >= java.math.BigInteger.ONE && it <= java.math.BigInteger.valueOf(29) }) {
            throw com.fasterxml.jackson.databind.JsonMappingException.from(parser, "INVALID_BUS_LEG_INDEX")
        }
        val index = parser.intValue
        if (index % 2 != 1) throw com.fasterxml.jackson.databind.JsonMappingException.from(parser, "INVALID_BUS_LEG_INDEX")
        return index
    }
}
data class JourneyBusStopOccurrence(val sourceIndex: Int, val providerSequence: Int, val localStationId: String,
    val latitude: Double, val longitude: Double)
data class JourneyBusRouteBinding(val status: String, val query: JourneyBusArrivalQuery? = null,
    val boarding: JourneyBusStopOccurrence? = null)
data class JourneyBusBindingResult(val sessionId: String, val generation: Long, val routeRevision: Long,
    val routeInputDigest: String, val mapping: JourneyBusRouteBinding) {
    val schemaVersion: Int get() = 1
    val identityEvidence: String get() = "SAVED_ROUTE_METADATA"
}

data class JourneyBusArrivalResult(
    val sessionId: String,
    val generation: Long,
    val routeRevision: Long,
    val routeInputDigest: String,
    val query: JourneyBusArrivalQuery,
    val evaluatedAt: Instant,
    val status: String,
    val observation: JourneyTransitObservation?,
    val incomingVehicleId: String?,
    val identityEvidence: String = "REQUEST_IDENTIFIERS_ONLY",
) {
    val schemaVersion: Int get() = 1
    // Request identity matching is not proof of a saved-route crosswalk or of the user's ride.
    val confirmedBoarding: Boolean get() = false
    val remainingStopsToAlight: Int? get() = null
}
