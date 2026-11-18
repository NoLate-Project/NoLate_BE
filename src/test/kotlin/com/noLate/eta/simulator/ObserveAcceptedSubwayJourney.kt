package com.noLate.eta.simulator

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.domain.SeoulSubwayLine
import com.noLate.eta.infrastructure.seoul.SeoulTrainPositionClient
import com.noLate.transit.infrastructure.TransitProviderApplicationException
import com.noLate.transit.infrastructure.TransitProviderWireMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Properties

/** Records public train position snapshots for an already accepted, isolated itinerary. */
object ObserveAcceptedSubwayJourney {
    private const val MAX_REQUESTS = 24
    private val seoul = ZoneId.of("Asia/Seoul")

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2)
        val output = Path.of(args[0]).toRealPath()
        val input = Path.of(args[1]).toRealPath()
        require(output.toString().startsWith("/private/tmp/nolate-accepted-train-observation."))
        require(input.parent.toString().startsWith("/private/tmp/nolate-eta-real-e2e.") &&
            input.fileName.toString().matches(Regex("evaluation-[1-3]\\.json")))
        val mapper = jacksonObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val worker = mapper.readTree(input.toFile())
        require(worker.path("classification").asText() == "REAL_PROVIDER_ISOLATED_WORKER" &&
            worker.path("database").asText() == "H2_MEMORY_ONLY" &&
            worker.path("providersMocked").isBoolean && !worker.path("providersMocked").booleanValue() &&
            worker.path("clockMode").asText() == "SYSTEM_WALL_CLOCK" && worker.path("source").asText() == "LIVE_PROVIDER")
        val snapshot = worker.path("effectiveSnapshot")
        require(snapshot.path("accepted").asBoolean() && snapshot.path("routeChanged").isBoolean &&
            !snapshot.path("routeChanged").booleanValue())
        val rides = snapshot.path("effectiveTransitRoute").path("segments")
            .filter { it.path("kind").asText() == "SUBWAY" }
        require(rides.size == 2)
        val today = Instant.now().atZone(seoul).toLocalDate().toString()
        val windows = rides.flatMap { segment ->
            val timing = segment.path("rideTiming")
            val table = timing.path("timetable")
            require(table.path("serviceDate").asText() == today)
            listOf(window(segment, timing, table, "BOARDING"), window(segment, timing, table, "ALIGHTING"))
        }
        val now = Instant.now()
        val nextMinute = now.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60)
        val due = windows.flatMap { window ->
            generateSequence(maxOf(window.from.truncatedTo(ChronoUnit.MINUTES), nextMinute)) { it.plusSeconds(60) }
                .takeWhile { it <= window.until }.map { it to window.line }.toList()
        }.distinct().sortedBy { it.first }
        require(due.isNotEmpty() && due.size <= MAX_REQUESTS && due.last().first <= now.plusSeconds(90 * 60))
        val settings = Properties().apply { Files.newBufferedReader(Path.of("env.local.properties")).use(::load) }
        val registry = SimpleMeterRegistry()
        val positions = SeoulTrainPositionClient(settings.getProperty("SEOUL_OPENAPI_KEY", ""),
            settings.getProperty("SEOUL_SUBWAY_API_KEY", ""),
            settings.getProperty("SEOUL_SUBWAY_BASE_URL", "http://swopenAPI.seoul.go.kr/api/subway"),
            allowInsecureHttp = settings.getProperty("SEOUL_ALLOW_INSECURE_HTTP") == "true",
            wireMetrics = TransitProviderWireMetrics(registry), maxDailyCalls = MAX_REQUESTS)
        Files.writeString(output.resolve("plan.json"), mapper.writeValueAsString(mapOf(
            "sourceEvaluation" to input.toString(), "journeyDepartureAt" to worker.path("recommendedDepartureAt").asText(),
            "journeyPredictedArrivalAt" to worker.path("predictedArrivalAt").asText(),
            "windows" to windows, "plannedRequests" to due.size, "maximumRequests" to MAX_REQUESTS,
        )), CREATE_NEW)
        println("Scheduled ${due.size} public snapshots. First ${due.first().first}, last ${due.last().first}.")
        var completed = 0
        var failureCode: String? = null
        try {
            Files.newBufferedWriter(output.resolve("positions.jsonl"), CREATE_NEW).use { stream ->
                val lastFreshRead = mutableMapOf<SeoulSubwayLine, Instant>()
                for ((at, line) in due) {
                    // Start after the adapter's 60s cache expires, measured from receivedAt.
                    val earliestWireRead = lastFreshRead[line]?.plusSeconds(61)
                    val requestAt = maxOf(at, earliestWireRead ?: at)
                    val wait = requestAt.toEpochMilli() - System.currentTimeMillis()
                    if (wait > 0) Thread.sleep(wait)
                    val response = positions.positions(line)
                    lastFreshRead[line] = response.receivedAt
                    val expected = windows.filter { it.line == line }
                    val matching = response.positions.filter { position ->
                        expected.any { window -> position.trainNumber in window.trainNumbers &&
                            position.directionCode == window.direction &&
                            position.serviceClass.name == window.serviceClass &&
                            stationKey(position.terminalName) == stationKey(window.terminal) }
                    }
                    val identityChecks = expected.filter { it.from.truncatedTo(ChronoUnit.MINUTES) <= at && at <= it.until }
                        .associate { window ->
                            val byNumber = response.positions.filter { it.trainNumber in window.trainNumbers }
                            val byDirection = byNumber.filter { it.directionCode == window.direction }
                            val byServiceClass = byDirection.filter { it.serviceClass.name == window.serviceClass }
                            val byTerminal = byServiceClass.filter { stationKey(it.terminalName) == stationKey(window.terminal) }
                            "${window.event}:${window.trainNumbers.first()}" to mapOf(
                                "numberRows" to byNumber.size, "directionRows" to byDirection.size,
                                "serviceClassRows" to byServiceClass.size, "terminalRows" to byTerminal.size,
                                "freshRows" to byTerminal.count { it.isFresh() },
                                "differentTerminalNames" to byServiceClass.filter { stationKey(it.terminalName) != stationKey(window.terminal) }
                                    .map { it.terminalName }.distinct().sorted(),
                            )
                        }
                    stream.write(mapper.writeValueAsString(mapOf("scheduledAt" to at, "line" to line,
                        "receivedAt" to response.receivedAt, "freshRows" to response.positions.count { it.isFresh() },
                        "rejectedRows" to response.rejectedRows, "matches" to matching, "identityChecks" to identityChecks)))
                    stream.newLine()
                    stream.flush()
                    completed++
                }
            }
        } catch (failure: Exception) {
            failureCode = if (failure is TransitProviderApplicationException) failure.code else failure.javaClass.simpleName
        } finally {
            val httpCalls = registry.find("nolate.eta.transit.provider.wire.duration").timers().sumOf { it.count() }
            Files.writeString(output.resolve("collection-report.json"), mapper.writeValueAsString(mapOf(
                "classification" to "PUBLIC_TRAIN_POSITION_OBSERVATION",
                "completedSnapshots" to completed, "plannedSnapshots" to due.size, "physicalHttpCalls" to httpCalls,
                "maximumHttpCalls" to MAX_REQUESTS, "failureCode" to failureCode,
                "actualTripAccuracyVerified" to false, "productionDatabaseModified" to false,
            )), CREATE_NEW)
            registry.close()
        }
        check(completed == due.size && failureCode == null) { "Public snapshot collection stopped without retry" }
    }

    private fun window(segment: JsonNode, timing: JsonNode, table: JsonNode, event: String): Window {
        val boarding = event == "BOARDING"
        val predicted = Instant.parse(timing.path(if (boarding) "predictedBoardingAt" else "predictedArrivalAt").asText())
        val scheduled = Instant.parse(table.path(if (boarding) "boardingAt" else "arrivalAt").asText())
        val number = table.path("trainNumber").asText()
        require(number.isNotBlank() && table.path("provider").asText() == "seoul-timetable")
        val trainNumbers = listOfNotNull(number, table.path("realtimeTrainNumber").asText(null)?.takeIf(String::isNotBlank))
        return Window(event, SeoulSubwayLine.valueOf(table.path("line").asText()), trainNumbers,
            table.path("directionCode").asText(), table.path("serviceClass").asText(), table.path("terminalName").asText(),
            segment.path(if (boarding) "fromName" else "toName").asText(), predicted, scheduled,
            minOf(predicted, scheduled).minusSeconds(60), maxOf(predicted, scheduled).plusSeconds(120))
    }

    // The position feed reports the same endpoint as "성수종착", while the timetable uses "성수".
    private fun stationKey(name: String) = name.replace(Regex("\\s+"), "")
        .removeSuffix("종착").removeSuffix("행").removeSuffix("역")

    private data class Window(
        val event: String, val line: SeoulSubwayLine, val trainNumbers: List<String>,
        val direction: String, val serviceClass: String, val terminal: String, val station: String,
        val predictedAt: Instant, val scheduledAt: Instant, val from: Instant, val until: Instant,
    )
}
