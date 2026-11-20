package com.noLate.transit.infrastructure

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.application.port.TransitTimetableQuery
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Properties

/** One opt-in, read-only schedule comparison. No server, DB, simulator, retry, or inferred train alias. */
object CompareRecordedAndDatedTimetable {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2)
        val root = Path.of(args[0]).toRealPath()
        val output = Path.of(args[1]).toAbsolutePath().normalize()
        require(root == Path.of("/private/tmp/nolate-eta-real-e2e.w9vtd97p"))
        require(output.parent.parent == Path.of("/private/tmp") &&
            output.parent.fileName.toString().startsWith("nolate-dated-timetable-comparison."))
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val completed = mapper.readTree(root.resolve("report.json").toFile())
        require(completed.path("originalAppEndpointRestored").asBoolean() &&
            !completed.path("productionDatabaseModified").asBoolean(true))
        val evaluation = mapper.readTree(root.resolve("evaluation-3.json").toFile())
        val frame = evaluation.path("plannedRefreshReplay").path("frames").last()
        val queryNode = frame.path("timetables").single().path("query")
        val query = mapper.treeToValue(queryNode, TransitTimetableQuery::class.java)
        val segments = frame.path("plan").path("segments")
        val lastRide = segments.last { !it.path("rideTiming").isNull && it.has("rideTiming") }
        val stops = lastRide.path("orderedStopNames").map { it.asText() }
        require(query.fromName == "사당" && query.toName == "신림" &&
            stops == listOf("사당", "낙성대", "서울대입구", "봉천", "신림"))
        val now = Instant.now()
        val seoul = ZoneId.of("Asia/Seoul")
        require(now.atZone(seoul).toLocalDate() == LocalDate.of(2026, 11, 20) &&
            now > Instant.parse(evaluation.path("evaluatedAt").asText()) &&
            query.serviceDate == now.atZone(seoul).toLocalDate())
        val settings = Properties().apply {
            Files.newBufferedReader(Path.of("/Users/mac/IdeaProjects/NoLate/NoLate_BE/env.local.properties")).use(::load)
        }
        val key = settings.getProperty("DATA_GO_KR_SUBWAY_PATH_API_KEY", "")
        require(key.isNotBlank())
        val result = linkedMapOf<String, Any?>(
            "classification" to "ONE_RECORDED_READY_TIME_VERSUS_DATE_SPECIFIC_OFFICIAL_SCHEDULE",
            "startedAt" to now.toString(), "recordedEvaluation" to "evaluation-3.json",
            "maximumClientFetches" to 1, "clientFetchesStarted" to 0,
            "sourceQuery" to queryNode, "timeInputMode" to "EXPLICIT_RECORDED_SCHEDULE_TIME_NOT_CURRENT_ETA",
            "sameTrainAliasInferred" to false, "passengerAccuracyVerified" to false,
            "productionDatabaseModified" to false, "simulatorModified" to false,
        )
        // CREATE_NEW is the one-attempt latch. A failure does not authorize another request or key.
        Files.writeString(output, mapper.writeValueAsString(result), StandardOpenOption.CREATE_NEW)
        try {
            result["clientFetchesStarted"] = 1
            Files.writeString(output, mapper.writeValueAsString(result), StandardOpenOption.TRUNCATE_EXISTING)
            val response = SeoulMetroShortestPathClient(key).use { client ->
                client.fetchScheduled(SeoulMetroShortestPathClient.Query(query.fromName, query.toName,
                    query.notBefore.atZone(seoul).toLocalDateTime()), Duration.ofSeconds(4))
            }
            val fetchedAt = Instant.now()
            result["parsedOfficialSections"] = response.sections
            result["validatedSameOrderedRide"] = bindRide(query, stops, response, fetchedAt)
            result["fetchedAt"] = fetchedAt.toString()
            result["result"] = "RESPONSE_CAPTURED_NOT_AN_ALIAS_OR_ACCURACY_CLAIM"
        } catch (_: Exception) {
            // Never serialize URLs, secret-bearing exceptions, or error response bodies.
            result["result"] = "PROVIDER_OR_CONTRACT_ERROR_STOP_NO_RETRY"
        }
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result),
            StandardOpenOption.TRUNCATE_EXISTING)
        println(mapper.writeValueAsString(mapOf("result" to result["result"], "clientFetchesStarted" to 1)))
    }
}
