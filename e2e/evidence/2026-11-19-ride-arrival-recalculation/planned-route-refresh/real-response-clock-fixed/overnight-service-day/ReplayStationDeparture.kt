import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.application.port.TransitJourneyProvider
import com.noLate.eta.application.port.TransitTimetableProvider
import com.noLate.eta.application.port.TransitTimetableQuery
import com.noLate.eta.application.transit.TransitJourneyTimetableEnricher
import com.noLate.eta.application.transit.TransitJourneyMatcher
import com.noLate.eta.domain.*
import com.noLate.eta.infrastructure.routejson.SelectedTransitRouteDecoder
import com.noLate.eta.resilience.EtaCalculationDeadline
import com.noLate.route.application.TransitTimetablePreviewRequest
import com.noLate.route.application.TransitTimetablePreviewService
import com.noLate.eta.infrastructure.seoul.SeoulTimetableServiceDay
import com.noLate.eta.infrastructure.seoul.SeoulTransitTimetableProvider
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.concurrent.atomic.AtomicInteger
import java.time.*

/** Replays a station-origin request using the recorded late train rows and the original exit walk.
 * The station-origin departure and appointment are controlled inputs, distinct from the original journey.
 * Pass the real-response-clock-fixed directory and a new JSON output path.
 */
fun main(args: Array<String>) {
    val root = Path.of(args[0])
    val mapper = jacksonObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    val capture = mapper.readTree(root.resolve("overnight-service-day/inputs.json").toFile())
    val plan = mapper.readTree(root.resolve("terminal-identification/trace-input.json").toFile())["plan"]
    val row = capture["normalizedRows"][0]
    val exitSeconds = plan["segments"].last()["durationSeconds"].longValue()
    val evaluated = Instant.parse(capture["evaluationAt"].textValue())
    val day = LocalDate.parse(capture["serviceDate"].textValue())
    val capturedRows = capture["normalizedRows"]
    val archived = mapper.readTree(root.resolve("evaluation-1.json").toFile())
    val event = archived["timetableSelectionTrace"]["events"].first { it["rides"] == capturedRows }
    val recordedQuery = mapper.treeToValue(event["query"], TransitTimetableQuery::class.java)
    val boundQueries = mutableListOf<TransitTimetableQuery>()
    mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    val seed = mapper.readValue(root.resolve("odsay-seed-journey.json").toFile(), TransitJourney::class.java)
    val selectedRide = seed.rideLegs.single { it.from?.name == recordedQuery.fromName && it.to?.name == recordedQuery.toName }
    val matchedResponse = object : TransitTimetableProvider {
        override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> {
            boundQueries.add(query)
            if (query != recordedQuery) return emptyList()
            return capturedRows.map { mapper.treeToValue(it, TransitLegTimetable::class.java) }
        }
    }
    val departure = Instant.parse("2026-11-19T15:06:00Z")
    val target = Instant.parse("2026-11-19T15:20:00Z")
    val board = Instant.parse(row["boardingAt"].textValue())
    val ride = TransitJourneyLeg(0, TransitLegMode.SUBWAY, 8,
        from = TransitStop(name = "사당"), to = TransitStop(name = "신림"),
        directionCode = row["directionCode"].textValue(), line = TransitLine(
            seoulSubwayLine = SeoulSubwayLine.valueOf(row["line"].textValue()), serviceClass = TransitServiceClass.LOCAL))
    val journey = TransitJourney("odsay", departure, departure, target, 14,
        listOf(ride, TransitJourneyLeg(1, TransitLegMode.WALK, 2, durationSeconds = exitSeconds)), evaluated)
    val reads = AtomicInteger()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
        val path = exchange.requestURI.path.trim('/').split('/')
        val service = path[2]
        val rows = capture["loopbackEnvelopes"][(listOf(service) + path.drop(5)).joinToString("/")]
        if (rows == null) {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return@createContext
        }
        val body = mapper.writeValueAsBytes(mapOf(service to mapOf(
            "RESULT" to mapOf("CODE" to "INFO-000"), "list_total_count" to rows.size(), "row" to rows)))
        reads.incrementAndGet()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
    server.start()
    try {
        val dates = mutableListOf<String>()
        val provider = SeoulTransitTimetableProvider("loopback", "http://127.0.0.1:"+server.address.port,
            SeoulTimetableServiceDay { queried ->
                dates.add(queried.toString())
                capture["weekTag"].textValue().takeIf { queried == day }
            },
            Clock.fixed(evaluated, ZoneOffset.UTC), true)
        val planner = TransitJourneyTimetableEnricher(listOf(provider))
        // Move only the origin to the saved boarding station. All fields of the station query
        // (including readiness, next stop, nullable direction and service date) must match the capture.
        val stationStart = recordedQuery.notBefore.minusSeconds(60)
        val topology = object : TransitJourneyProvider {
            override val providerId = seed.provider
            override fun search(request: TransitJourneySearchRequest): List<TransitJourney> = listOf(
                journey.copy(requestedDepartureAt = request.departureAt, departureAt = request.departureAt,
                    legs = listOf(selectedRide.copy(sequence = 0), journey.legs.last())))
        }
        val seedJson = mapper.readTree(root.resolve("odsay-seed-journey.json").toFile())
        val station = seedJson["legs"][2]["walkingGeometry"]["pathEvidence"]["steps"].last()["coordinates"].last()
        val destination = seedJson["exitWalkEndpoints"]["end"]
        val selection = mapper.readTree(root.resolve("selected-route.json").toFile())["transitLegs"].single {
            it["startID"]?.asText() == selectedRide.from?.providerStopId && it["endID"]?.asText() == selectedRide.to?.providerStopId
        }
        val service = TransitTimetablePreviewService(listOf(topology), SelectedTransitRouteDecoder(mapper),
            TransitJourneyMatcher(), TransitJourneyTimetableEnricher(listOf(matchedResponse)),
            Clock.fixed(evaluated, ZoneOffset.UTC), EtaCalculationDeadline())
        val savedLegPlan = service.preview(TransitTimetablePreviewRequest(
            station["lng"].doubleValue(), station["lat"].doubleValue(), destination["lng"].doubleValue(),
            destination["lat"].doubleValue(), requestedDepartureAt = stationStart,
            route = mapper.valueToTree(mapOf("provider" to seed.provider, "transitLegs" to listOf(selection))),
            nextStops = listOf(requireNotNull(selectedRide.nextStopName)),
        )).preview
        val output = mapOf("recordedStationQuery" to recordedQuery, "issuedStationQueries" to boundQueries,
            "sameOdsayStationQuery" to savedLegPlan,"evaluationAt" to evaluated, "requestedDepartureAt" to departure, "targetArrivalAt" to target,
            "departAtStation" to planner.planDepartingAt(journey, departure, evaluated, 60),
            "arriveBy" to planner.planArrivalBy(journey, target, evaluated, 60),
            "oneSecondPastBoardingCutoff" to planner.planDepartingAt(journey, board.minusSeconds(59), evaluated, 60),
            "checkedCalendarDates" to dates, "loopbackResponses" to reads.get())
        Files.newOutputStream(Path.of(args[1]), CREATE_NEW).use { mapper.writerWithDefaultPrettyPrinter().writeValue(it, output) }
    } finally {
        server.stop(0)
    }
}
