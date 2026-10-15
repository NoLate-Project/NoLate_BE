package com.noLate.intercity

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.noLate.global.error.BusinessException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class IntercityJourneyValidatorTest {
    private val mapper = ObjectMapper()
    private val departure = Instant.parse("2026-10-12T01:30:00Z")
    private val target = Instant.parse("2026-10-12T05:00:00Z")
    private fun route(): ObjectNode {
        val service = mapper.readTree("""{"id":"tago:test","kind":"TRAIN","departureId":"NATH30000","arrivalId":"NATH20438","departureAt":"2026-10-12T11:00:00+09:00","arrivalAt":"2026-10-12T12:00:00+09:00","serviceDate":"2026-10-12","timingBasis":"TIMETABLE","bookingStatus":"UNCONFIRMED"}""")
        val journey = mapper.readTree("""{"version":1,"departureAt":"2026-10-12T01:30:00Z","arrivalAt":"2026-10-12T03:25:00Z","boardingBufferMinutes":15,"alightingBufferMinutes":5,"connectionBasis":"ROUTE_ESTIMATE","mapping":{"confirmed":true}}""") as ObjectNode
        journey.set<ObjectNode>("service", service)
        val route = mapper.readTree("""{"schemaVersion":3,"providerTimeBasis":"route_estimate","totalDurationSeconds":6900,"routeInfo":{"timeBasis":"anchored_journey","departureTime":"2026-10-12T01:30:00Z","arrivalTime":"2026-10-12T03:25:00Z","totalDurationSeconds":6900},"transitLegs":[{"kind":"BUS","durationSeconds":900,"startDateTime":"2026-10-12T01:30:00Z","endDateTime":"2026-10-12T01:45:00Z"},{"kind":"ETC","journeyPhase":"BUFFER","durationSeconds":900,"startDateTime":"2026-10-12T01:45:00Z","endDateTime":"2026-10-12T02:00:00Z"},{"kind":"TRAIN","durationSeconds":3600,"startDateTime":"2026-10-12T02:00:00Z","endDateTime":"2026-10-12T03:00:00Z"},{"kind":"ETC","journeyPhase":"BUFFER","durationSeconds":300,"startDateTime":"2026-10-12T03:00:00Z","endDateTime":"2026-10-12T03:05:00Z"},{"kind":"BUS","durationSeconds":1200,"startDateTime":"2026-10-12T03:05:00Z","endDateTime":"2026-10-12T03:25:00Z"}]}""") as ObjectNode
        route.set<ObjectNode>("intercityJourney", journey)
        (route.path("routeInfo") as ObjectNode).set<ObjectNode>("intercityJourney", journey.deepCopy())
        (route.path("transitLegs").get(2) as ObjectNode).set<ObjectNode>("intercityService", service.deepCopy())
        return route
    }
    @Test fun `mixed journey is accepted without converting connections into timetable or booking`() {
        assertDoesNotThrow { IntercityJourneyValidator.validate(route(), departure, 115, target) }
    }
    @Test fun `legacy routes are unaffected`() {
        assertDoesNotThrow { IntercityJourneyValidator.validate(mapper.readTree("""{"schemaVersion":2}"""), null, null, null) }
    }
    @Test fun `appointment back calculation cannot move the departure`() {
        assertThrows(BusinessException::class.java) { IntercityJourneyValidator.validate(route(), departure.plusSeconds(3600), 115, target) }
    }
    @Test fun `late arrival and rounded down duration are rejected`() {
        assertThrows(BusinessException::class.java) { IntercityJourneyValidator.validate(route(), departure, 114, target) }
        assertThrows(BusinessException::class.java) { IntercityJourneyValidator.validate(route(), departure, 115, Instant.parse("2026-10-12T03:20:00Z")) }
    }
    @Test fun `changed ride time duplicate ride and invalid duration are rejected`() {
        val shifted = route(); (shifted.path("transitLegs").get(2) as ObjectNode).put("startDateTime", "2026-10-12T02:01:00Z")
        val duplicate = route(); (duplicate.path("transitLegs").get(0) as ObjectNode).put("kind", "TRAIN")
        val corrupt = route(); (corrupt.path("transitLegs").get(0) as ObjectNode).put("durationSeconds", -1)
        listOf(shifted, duplicate, corrupt).forEach { assertThrows(BusinessException::class.java) { IntercityJourneyValidator.validate(it, departure, 115, target) } }
    }
    @Test fun `copies must agree and whole journey may not claim timetable`() {
        val route = route(); route.put("providerTimeBasis", "timetable")
        assertThrows(BusinessException::class.java) { IntercityJourneyValidator.validate(route, departure, 115, target) }
        val inconsistent = route(); (inconsistent.path("routeInfo").path("intercityJourney") as ObjectNode).put("boardingBufferMinutes", 10)
        assertThrows(BusinessException::class.java) { IntercityJourneyValidator.validate(inconsistent, departure, 115, target) }
    }
    @Test fun `public timetable cannot claim a confirmed reservation or skip mapping confirmation`() {
        val route = route(); (route.path("intercityJourney").path("service") as ObjectNode).put("bookingStatus", "CONFIRMED")
        assertThrows(BusinessException::class.java) { IntercityJourneyValidator.validate(route, departure, 115, target) }
        val unconfirmed = route(); (unconfirmed.path("intercityJourney").path("mapping") as ObjectNode).put("confirmed", false)
        assertThrows(BusinessException::class.java) { IntercityJourneyValidator.validate(unconfirmed, departure, 115, target) }
    }
    private fun ticketRoute(): ObjectNode {
        val route = route()
        val ticket = mapper.readTree("""{"version":1,"id":"ticket:12345678-1234-1234-1234-123456789012","kind":"TRAIN","source":"IOS_SHARE_IMAGE","verification":"USER_CONFIRMED","bookingStatus":"UNVERIFIED","departureName":"서울","arrivalName":"부산","departureAt":"2026-10-12T11:00:00+09:00","arrivalAt":"2026-10-12T12:00:00+09:00","trainNumber":"123","grade":"KTX","confirmedAt":"2026-10-11T00:00:00Z"}""")
        val service = ticket.deepCopy<ObjectNode>()
        service.put("timingBasis", "TICKET").put("serviceDate", "2026-10-12")
            .put("departureId", ticket.path("id").asText() + ":departure").put("arrivalId", ticket.path("id").asText() + ":arrival")
        route.set<ObjectNode>("importedTicket", ticket)
        (route.path("intercityJourney") as ObjectNode).set<ObjectNode>("service", service)
        (route.path("routeInfo").path("intercityJourney") as ObjectNode).set<ObjectNode>("service", service.deepCopy())
        (route.path("transitLegs").get(2) as ObjectNode).set<ObjectNode>("intercityService", service.deepCopy())
        return route
    }
    @Test fun `reviewed ticket anchor and unconnected import are accepted without claiming verified booking`() {
        assertDoesNotThrow { IntercityJourneyValidator.validate(ticketRoute(), departure, 115, target) }
        val pending = ticketRoute(); pending.remove("intercityJourney")
        assertDoesNotThrow { IntercityJourneyValidator.validate(pending, null, null, target) }
    }
    @Test fun `ticket time mismatch missing original and invented carrier confirmation are rejected`() {
        val mismatch = ticketRoute(); (mismatch.path("importedTicket") as ObjectNode).put("trainNumber", "124")
        val missing = ticketRoute(); missing.remove("importedTicket")
        val confirmed = ticketRoute(); (confirmed.path("importedTicket") as ObjectNode).put("bookingStatus", "CONFIRMED")
        listOf(mismatch, missing, confirmed).forEach { assertThrows(BusinessException::class.java) { IntercityJourneyValidator.validate(it, departure, 115, target) } }
    }
    @Test fun `malformed imported ticket is rejected even before a route is connected`() {
        val pending = ticketRoute(); pending.remove("intercityJourney")
        (pending.path("importedTicket") as ObjectNode).put("arrivalAt", "2026-10-12T10:00:00+09:00")
        assertThrows(BusinessException::class.java) { IntercityJourneyValidator.validate(pending, null, null, target) }
    }

    @Test fun `bus PDF ticket preserves grade and kind while rejecting grade or kind tampering`() {
        for (kind in listOf("EXPRESS_BUS", "INTERCITY_BUS")) {
            val route = ticketRoute()
            val ticket = route.path("importedTicket") as ObjectNode
            ticket.put("kind", kind).put("source", "IOS_SHARE_PDF").put("grade", "우등"); ticket.remove("trainNumber")
            for (service in listOf(route.path("intercityJourney").path("service"), route.path("routeInfo").path("intercityJourney").path("service"), route.path("transitLegs").get(2).path("intercityService"))) {
                (service as ObjectNode).put("kind", kind).put("grade", "우등"); service.remove("trainNumber")
            }
            (route.path("transitLegs").get(2) as ObjectNode).put("kind", kind)
            assertDoesNotThrow { IntercityJourneyValidator.validate(route, departure, 115, target) }
            ticket.put("grade", "일반")
            assertThrows(BusinessException::class.java) { IntercityJourneyValidator.validate(route, departure, 115, target) }
            ticket.put("grade", "우등").put("kind", "TRAIN")
            assertThrows(BusinessException::class.java) { IntercityJourneyValidator.validate(route, departure, 115, target) }
        }
    }

}
