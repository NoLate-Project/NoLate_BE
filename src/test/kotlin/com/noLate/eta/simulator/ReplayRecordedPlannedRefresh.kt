package com.noLate.eta.simulator

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.application.port.TransitTimetableProvider
import com.noLate.eta.application.port.TransitTimetableQuery
import com.noLate.eta.application.transit.*
import com.noLate.eta.domain.TransitLegTimetable
import com.noLate.schedule.application.EffectiveTransitRoute
import com.noLate.schedule.application.PlannedTransitRouteUpdate
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.TransitArrivalDto
import org.mockito.kotlin.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/** Offline comparison of production refresh implementations on the same recorded, normalized provider responses. */
internal object ReplayRecordedPlannedRefresh {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    // The production guard handles provider Exceptions. Missing evidence must escape that fallback.
    private class MissingRead(message: String) : AssertionError(message)

    fun replay(config: JsonNode, frame: JsonNode, verifyCapture: Boolean): Map<String, Any?> {
        return try { replayFrame(config, frame, verifyCapture) } catch (failure: JsonMappingException) {
            if (verifyCapture) throw failure
            mapOf("result" to "NOT_COMPARABLE", "reason" to "INPUT_SCHEMA_NOT_SUPPORTED")
        }
    }

    private fun replayFrame(config: JsonNode, frame: JsonNode, verifyCapture: Boolean): Map<String, Any?> {
        if (!frame.path("incomplete").isBoolean || frame.path("incomplete").asBoolean())
            return mapOf("result" to "NOT_COMPARABLE", "reason" to "INCOMPLETE_CAPTURE")
        val plan = mapper.treeToValue(frame.required("plan"), EffectiveTransitRoute::class.java)
        val departure = mapper.treeToValue(frame.required("departurePlan"), EffectiveTransitRoute::class.java)
        val previous = frame.get("previousUpdate")?.takeUnless { it.isNull }?.let {
            mapper.treeToValue(it, PlannedTransitRouteUpdate::class.java)
        }
        if ((plan.segments + departure.segments).any { it.rideTiming?.timetable?.provider?.let { p -> p != "seoul-timetable" } == true })
            return mapOf("result" to "NOT_COMPARABLE", "reason" to "UNRECORDED_DATED_TIMETABLE_PROVIDER")
        val now = Instant.parse(frame.required("evaluatedAt").textValue())
        val stations = frame.required("stations").also { require(it.isArray) }.toList()
        val timetables = frame.required("timetables").also { require(it.isArray) }.toList()
        val arrivals = mock<TransitArrivalService>()
        var stationIndex = 0
        var stationReadsUsed = 0
        var tableIndex = 0
        var missingRead: String? = null
        fun missing(reason: String): Nothing {
            missingRead = reason
            throw MissingRead(reason)
        }
        whenever(arrivals.getSubwayArrivalsForEta(any(), anyOrNull(), anyOrNull(), anyOrNull(), any())).thenAnswer { call ->
            if (stationIndex >= stations.size) missing("UNRECORDED_STATION_QUERY")
            fun key(entry: JsonNode) = listOf("station", "line", "directionName", "directionCode").map {
                entry.get(it)?.textValue()
            } + entry.required("limit").intValue()
            // 수정 전 구현은 보충 역을 조회하지 않을 수 있다. 같은 회차에서 이미 기록한
            // 동일 쿼리까지 사용하지 않는 행만 건너뛰며, 현재 구현은 원래 순서를 전부 재현한다.
            if (!verifyCapture) {
                val matching = (stationIndex until stations.size).firstOrNull { key(stations[it]) == call.arguments.toList() }
                    ?: missing("DIFFERENT_STATION_QUERY")
                stationIndex = matching
            }
            val entry = stations[stationIndex]
            if (key(entry) != call.arguments.toList()) missing("DIFFERENT_STATION_QUERY")
            stationIndex++
            stationReadsUsed++
            try {
                entry.required("rows").also { require(it.isArray) }.map { mapper.treeToValue(it, TransitArrivalDto::class.java) }
            } catch (failure: JsonMappingException) {
                missing("INPUT_SCHEMA_NOT_SUPPORTED")
            }
        }
        val providers = List(config.required("timetableProviderCount").intValue()) { index ->
            object : TransitTimetableProvider {
                override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> {
                    val entry = timetables.getOrNull(tableIndex) ?: missing("UNRECORDED_TIMETABLE_QUERY")
                    try {
                        if (entry.required("providerIndex").intValue() != index ||
                            mapper.treeToValue(entry.required("query"), TransitTimetableQuery::class.java) != query)
                            missing("DIFFERENT_TIMETABLE_QUERY")
                        tableIndex++
                        return entry.required("rides").also { require(it.isArray) }.map { mapper.treeToValue(it, TransitLegTimetable::class.java) }
                    } catch (failure: JsonMappingException) {
                        missing("INPUT_SCHEMA_NOT_SUPPORTED")
                    }
                }
            }
        }
        val margin = config.required("transferConfidenceMarginSeconds").longValue()
        val transferBuffer = config.required("transferBufferSeconds").longValue()
        val refresher = PlannedTransitRouteRefresher(TransitTransferRealtimeGuard(arrivals),
            TransitJourneyTimetableEnricher(providers, config.required("boardingBufferSeconds").longValue(), margin, transferBuffer),
            TransitTransferFeasibilityEvaluator(transferBuffer, margin), maxTravelMinutes = config.required("maxTravelMinutes").intValue())
        val result = try { refresher.refresh(plan, now, departure, previous) } catch (missing: MissingRead) {
            return mapOf("result" to "NOT_COMPARABLE", "reason" to missing.message, "evaluatedAt" to now)
        }
        // Reconnection can catch a provider failure and try another provider. No such fallback can supply missing evidence.
        if (missingRead != null) return mapOf("result" to "NOT_COMPARABLE", "reason" to missingRead, "evaluatedAt" to now)
        if (verifyCapture) {
            check(stationIndex == stations.size && tableIndex == timetables.size) { "Recorded requests were not reproduced" }
            val expected = frame.get("result")?.takeUnless { it.isNull }?.let { mapper.treeToValue(it, PlannedTransitRouteUpdate::class.java) }
            check(result == expected) { "Replayed update differs from the captured refresh result" }
        }
        return mapOf("result" to "REPLAYED", "evaluatedAt" to now, "stationReadsUsed" to stationReadsUsed,
            "capturedStationReadsNotUsed" to stations.size - stationReadsUsed,
            "timetableReadsUsed" to tableIndex, "verifiedAgainstRecordedResult" to verifyCapture, "update" to result)
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 2..3 && (args.size == 2 || args[2] == "--verify-capture")) {
            "Usage: ReplayRecordedPlannedRefresh evaluation.json new-report.json [--verify-capture]"
        }
        val worker = mapper.readTree(Path.of(args[0]).toFile())
        require(worker.path("classification").asText() == "REAL_PROVIDER_ISOLATED_WORKER" &&
            worker.path("database").asText() == "H2_MEMORY_ONLY" && worker.path("clockMode").asText() == "SYSTEM_WALL_CLOCK" &&
            worker.path("journeyProvider").asText() == "odsay" && worker.required("providersMocked").isBoolean &&
            !worker.required("providersMocked").booleanValue())
        val config = worker.required("plannedRefreshReplay")
        val frames = config.required("frames").also { require(it.isArray) }
        val reports = frames.map { replay(config, it, args.size == 3) }
        Files.newBufferedWriter(Path.of(args[1]), StandardOpenOption.CREATE_NEW).use {
            mapper.writerWithDefaultPrettyPrinter().writeValue(it, mapOf("additionalHttpCalls" to 0, "frames" to reports))
        }
    }
}
