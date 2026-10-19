package com.noLate.journey.domain

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

/** Session identity only. No client station, train, direction, pattern or GPS is accepted. */
data class JourneySubwayObservationCommand(val generation: Long, val routeRevision: Long,
    val clientOwnerId: String, val routeInputDigest: String,
    @JsonProperty(required = true)
    @JsonDeserialize(using = JourneySubwayLegIndexDeserializer::class)
    val subwayLegIndex: Int) {
    fun fence() = AutomaticJourneyStopCommand(generation,routeRevision,clientOwnerId,routeInputDigest)
}

class JourneySubwayLegIndexDeserializer : JsonDeserializer<Int>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): Int {
        if (parser.currentToken != JsonToken.VALUE_NUMBER_INT ||
            parser.bigIntegerValue !in java.math.BigInteger.ONE..java.math.BigInteger.valueOf(29))
            throw JsonMappingException.from(parser,"INVALID_SUBWAY_LEG_INDEX")
        return parser.intValue.also {
            if (it % 2 != 1) throw JsonMappingException.from(parser,"INVALID_SUBWAY_LEG_INDEX")
        }
    }
    override fun getNullValue(context: DeserializationContext): Int =
        throw JsonMappingException.from(context.parser,"INVALID_SUBWAY_LEG_INDEX")
}
