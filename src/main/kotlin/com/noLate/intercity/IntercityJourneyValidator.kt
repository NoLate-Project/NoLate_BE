package com.noLate.intercity

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** Structural validation only: a stored public timetable is never proof of a booking or live operation. */
object IntercityJourneyValidator {
    fun validate(route: JsonNode?, departAt: Instant?, travelMinutes: Int?, targetArrivalAt: Instant?) {
        fun invalid(): Nothing = throw BusinessException(ErrorCode.INVALID_INPUT, "선택 운행편과 연결 경로의 시각이 일치하지 않습니다. 운행편을 다시 선택해 주세요.")
        fun time(node: JsonNode, field: String): Instant = runCatching { OffsetDateTime.parse(node.path(field).asText()).toInstant() }.getOrElse { invalid() }
        val ticket = route?.get("importedTicket")
        if (ticket != null) {
            if (!ticket.isObject || ticket.path("version").asInt() != 1 || ticket.path("kind").asText() !in setOf("TRAIN", "EXPRESS_BUS", "INTERCITY_BUS") ||
                !Regex("ticket:[0-9a-fA-F-]{36}").matches(ticket.path("id").asText()) ||
                ticket.path("source").asText() !in setOf("IOS_SHARE_TEXT", "IOS_SHARE_IMAGE", "IOS_SHARE_PDF") ||
                ticket.path("verification").asText() != "USER_CONFIRMED" || ticket.path("bookingStatus").asText() != "UNVERIFIED" ||
                (if (ticket.path("kind").asText() == "TRAIN") !Regex("[0-9]{1,6}").matches(ticket.path("trainNumber").asText()) else ticket.path("trainNumber").asText().isNotEmpty() || ticket.path("grade").asText().isBlank() || ticket.path("grade").asText().length > 40) ||
                listOf("departureName", "arrivalName").any { ticket.path(it).asText().isBlank() || ticket.path(it).asText().length > 60 } ||
                ticket.path("departureName").asText().trim() == ticket.path("arrivalName").asText().trim()) invalid()
            val ticketDepart = time(ticket, "departureAt")
            val ticketArrive = time(ticket, "arrivalAt")
            if (Duration.between(ticketDepart, ticketArrive).seconds !in 1..86400) invalid()
            for (field in listOf("departureAt", "arrivalAt")) {
                if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:00\\+09:00").matches(ticket.path(field).asText())) invalid()
            }
            time(ticket, "confirmedAt")
        }
        val journey = route?.get("intercityJourney") ?: return
        if (!journey.isObject || journey.path("version").asInt() != 1 || route.path("schemaVersion").asInt() != 3) invalid()
        val service = journey.path("service")
        val kind = runCatching { IntercityKind.valueOf(service.path("kind").asText()) }.getOrElse { invalid() }
        val fromTicket = service.path("timingBasis").asText() == "TICKET"
        if (fromTicket) {
            if (ticket == null || kind.name != ticket.path("kind").asText() || service.path("bookingStatus").asText() != "UNVERIFIED" ||
                listOf("id", "departureName", "arrivalName", "departureAt", "arrivalAt", "trainNumber", "grade").any { service.path(it).asText() != ticket.path(it).asText() }) invalid()
        } else if (ticket != null || !service.path("id").asText().startsWith("tago:") || service.path("timingBasis").asText() != "TIMETABLE" || service.path("bookingStatus").asText() != "UNCONFIRMED") invalid()
        if (journey.path("connectionBasis").asText() != "ROUTE_ESTIMATE" || !journey.path("mapping").path("confirmed").asBoolean()) invalid()
        val originId = service.path("departureId").asText()
        val destinationId = service.path("arrivalId").asText()
        if (fromTicket) {
            if (originId != "${ticket!!.path("id").asText()}:departure" || destinationId != "${ticket.path("id").asText()}:arrival") invalid()
        } else if (!originId.startsWith(kind.idPrefix) || !destinationId.startsWith(kind.idPrefix) || originId == destinationId) invalid()
        val depart = time(journey, "departureAt")
        val arrive = time(journey, "arrivalAt")
        val rideDepart = time(service, "departureAt")
        val rideArrive = time(service, "arrivalAt")
        val boarding = journey.path("boardingBufferMinutes")
        val alighting = journey.path("alightingBufferMinutes")
        if (!boarding.isIntegralNumber || boarding.asInt() !in 5..120 || !alighting.isIntegralNumber || alighting.asInt() !in 0..120) invalid()
        val total = Duration.between(depart, arrive).seconds
        if (total !in 1..86400 || departAt != depart || travelMinutes?.toLong() != (total + 59) / 60L || rideDepart <= depart || rideArrive <= rideDepart || arrive < rideArrive.plusSeconds(alighting.asLong() * 60) || (targetArrivalAt != null && arrive > targetArrivalAt)) invalid()
        if (OffsetDateTime.parse(service.path("departureAt").asText()).toLocalDate().toString() != service.path("serviceDate").asText()) invalid()
        val info = route.path("routeInfo")
        if (info.path("intercityJourney") != journey || info.path("timeBasis").asText() != "anchored_journey" || route.path("providerTimeBasis").asText() != "route_estimate" || time(info, "departureTime") != depart || time(info, "arrivalTime") != arrive || route.path("totalDurationSeconds").asLong() != total || info.path("totalDurationSeconds").asLong() != total) invalid()
        val legs = route.path("transitLegs")
        if (!legs.isArray || legs.isEmpty) invalid()
        var cursor = depart
        var fixedCount = 0
        for (leg in legs) {
            val duration = leg.path("durationSeconds")
            if (!duration.isNumber || !duration.asDouble().isFinite() || duration.asDouble() < 0 || duration.asDouble() != duration.asLong().toDouble() || time(leg, "startDateTime") != cursor) invalid()
            cursor = cursor.plusSeconds(duration.asLong())
            if (time(leg, "endDateTime") != cursor) invalid()
            if (leg.path("kind").asText() in setOf("TRAIN", "EXPRESS_BUS", "INTERCITY_BUS", "COACH")) {
                fixedCount++
                if (leg.path("kind").asText() != kind.name || leg.path("intercityService") != service || time(leg, "startDateTime") != rideDepart || cursor != rideArrive) invalid()
                val previous = legs.get(legs.indexOf(leg) - 1) ?: invalid()
                if (previous.path("journeyPhase").asText() != "BUFFER" || previous.path("durationSeconds").asLong() < boarding.asLong() * 60) invalid()
            }
        }
        if (fixedCount != 1 || cursor != arrive) invalid()
    }
}
