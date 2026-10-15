package com.noLate.eta.simulator

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.eta.domain.SeoulSubwayLine
import com.noLate.eta.domain.TransitServiceClass
import com.noLate.eta.application.port.TransitTimetableQuery
import com.noLate.eta.infrastructure.seoul.SeoulTrainPositionClient
import com.noLate.eta.infrastructure.seoul.SeoulTransitTimetableProvider
import com.noLate.eta.infrastructure.seoul.SeoulTimetableServiceDay
import com.noLate.calendar.infrastructure.KasiCalendarClient
import com.noLate.transit.infrastructure.*
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.time.Instant
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Properties

/** Bounded, read-only public-data collection. No Spring/DB/server/simulator or schedule mutation. */
object CollectTrainMovementEvidence {
    @JvmStatic fun main(args: Array<String>) {
        val output = Path.of(args[0]).toRealPath()
        require(output.toString().startsWith("/private/tmp/nolate-train-movement."))
        val cycles = args.getOrNull(1)?.toInt() ?: 1
        require(cycles in 1..60)
        val intervalSeconds = args.getOrNull(2)?.toLong() ?: 60L
        require(intervalSeconds in 60..120)
        val settings = Properties().apply { Files.newBufferedReader(Path.of("env.local.properties")).use(::load) }
        fun setting(key: String, default: String = "") = System.getenv(key) ?: settings.getProperty(key, default)
        val mapper = jacksonObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val registry = SimpleMeterRegistry()
        val metrics = TransitProviderWireMetrics(registry)
        val position = SeoulTrainPositionClient(setting("SEOUL_OPENAPI_KEY"), setting("SEOUL_SUBWAY_API_KEY"),
            setting("SEOUL_SUBWAY_BASE_URL", "http://swopenAPI.seoul.go.kr/api/subway"),
            allowInsecureHttp = setting("SEOUL_ALLOW_INSECURE_HTTP") == "true", wireMetrics = metrics, maxDailyCalls = cycles)
        val arrivals = SeoulTransitArrivalClient(setting("SEOUL_OPENAPI_KEY"), setting("SEOUL_SUBWAY_API_KEY"), "",
            setting("SEOUL_SUBWAY_BASE_URL", "http://swopenAPI.seoul.go.kr/api/subway"), "https://ws.bus.go.kr/api/rest",
            allowInsecureHttp = setting("SEOUL_ALLOW_INSECURE_HTTP") == "true", wireMetrics = metrics)
        val stream = Files.newBufferedWriter(output.resolve("observations.jsonl"), Charsets.UTF_8, CREATE_NEW, WRITE)
        var completed = 0
        var calendarCalls = 0
        var failureCode: String? = null
        try {
            val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
            val calendar = args.getOrNull(3)?.let { calendarPath ->
                val calendarFile = Path.of(calendarPath).toRealPath()
                require(calendarFile.toString().startsWith("/private/tmp/nolate-eta-real-e2e."))
                mapper.readTree(calendarFile.toFile()).also { existing ->
                    require(existing.path("date").asText() == today.toString() && existing.path("weekTag").asText() in setOf("1", "2", "3"))
                    require(existing.path("source").asText() == "EXISTING_VERIFIED_SERVICE_DAY")
                }
            } ?: run {
                // A new service day cannot inherit yesterday's weekday assumption.
                // Verify the current month once through the existing public KASI client.
                val kasi = KasiCalendarClient(setting("KASI_ENABLED", "true") == "true", setting("KASI_API_KEY"),
                    setting("KASI_BASE_URL", "https://apis.data.go.kr/B090041/openapi/service"))
                check(kasi.isAvailable()) { "Current service day needs a configured KASI key" }
                calendarCalls++
                val holidays = kasi.fetchHolidayMonth(YearMonth.from(today))
                val tag = when {
                    holidays.any { it.date == today } || today.dayOfWeek == DayOfWeek.SUNDAY -> "3"
                    today.dayOfWeek == DayOfWeek.SATURDAY -> "2"
                    else -> "1"
                }
                mapper.createObjectNode().put("source", "KASI_VERIFIED_CURRENT_MONTH")
                    .put("date", today.toString()).put("weekTag", tag)
                    .put("additionalKasiCalls", calendarCalls)
            }
            val timetable = SeoulTransitTimetableProvider(
                setting("SEOUL_TIMETABLE_API_KEY", setting("SEOUL_OPENAPI_KEY")),
                setting("SEOUL_TIMETABLE_BASE_URL", "http://openapi.seoul.go.kr:8088"),
                SeoulTimetableServiceDay { day -> calendar.path("weekTag").asText().takeIf { day == today } },
                allowInsecureHttp = setting("SEOUL_TIMETABLE_ALLOW_INSECURE_HTTP") == "true", wireMetrics = metrics,
            )
            val rides = timetable.findRides(TransitTimetableQuery(SeoulSubwayLine.LINE_4, "노원", "사당", "창동",
                TransitServiceClass.LOCAL, Instant.now().minusSeconds(300), "DOWN", today))
            check(rides.isNotEmpty()) { "No matched public timetable; do not collect unlabelled model samples" }
            Files.writeString(output.resolve("timetables.json"), mapper.writeValueAsString(mapOf(
                "classification" to "ACTUAL_PUBLIC_TIMETABLE_BASELINE_FOR_REPLAY", "calendar" to calendar,
                "fromName" to "노원", "toName" to "사당", "rides" to rides)), CREATE_NEW, WRITE)
            println(mapper.writeValueAsString(mapOf("state" to "actual_timetables_recorded", "rides" to rides.size)))
            repeat(cycles) { index ->
                // One shared 4-line snapshot and one public origin-station response,
                // never one request per train/user. At most 2*cycles wire requests.
                val positions = position.positions(SeoulSubwayLine.LINE_4)
                if (positions.positions.isEmpty() || positions.positions.none { it.isFresh() }) {
                    Files.writeString(output.resolve("unusable-position-response.json"), mapper.writeValueAsString(positions), CREATE_NEW, WRITE)
                    error("No usable fresh train positions; collection stopped without retry")
                }
                val origin = arrivals.getSubwayArrivals("노원", "4호선", null, "DOWN", 10)
                val record = mapOf("schemaVersion" to 1, "capturedAt" to Instant.now(), "positions" to positions,
                    "originForecasts" to origin.map { row -> mapOf("trainNumber" to row.trainNumber,
                        "stationName" to row.stationName, "lineName" to row.lineName, "direction" to row.direction,
                        "terminalName" to row.destinationName, "express" to row.express,
                        "sourceUpdatedAt" to row.sourceUpdatedAt, "expectedBoardingAt" to row.expectedAt,
                        "receivedAt" to row.observedAt, "arrivalStatus" to row.arrivalStatus) })
                stream.write(mapper.writeValueAsString(record)); stream.newLine(); stream.flush()
                completed++
                println(mapper.writeValueAsString(mapOf("state" to "collected", "cycle" to completed,
                    "receivedAt" to positions.receivedAt, "positions" to positions.positions.size,
                    "freshPositions" to positions.positions.count { it.isFresh() }, "rejectedRows" to positions.rejectedRows,
                    "originForecastCount" to origin.size)))
                if (index + 1 < cycles) Thread.sleep(intervalSeconds * 1000)
            }
        } catch (failure: Exception) {
            failureCode = if (failure is TransitProviderApplicationException) failure.code else failure.javaClass.simpleName
            println(mapper.writeValueAsString(mapOf("state" to "stopped_no_retry", "failureCode" to failureCode)))
        } finally {
            stream.close()
            val physical = registry.find("nolate.eta.transit.provider.wire.duration").timers().sumOf { it.count() }
            val report = mapOf("classification" to "REAL_PUBLIC_TRAIN_MOVEMENT_COLLECTION_NOT_E2E", "completedCycles" to completed,
                "requestedCycles" to cycles, "intervalSeconds" to intervalSeconds,
                "physicalHttpCalls" to physical + calendarCalls,
                "maximumHttpCalls" to cycles * 2 + 6 + if (args.size > 3) 0 else 1,
                "calendarVerificationCallCount" to calendarCalls,
                "failureCode" to failureCode, "clockMode" to "SYSTEM_WALL_CLOCK", "providersMocked" to false,
                "productionDatabaseModified" to false, "serversOrSimulatorsModified" to false,
                "exactPhysicalArrivalSecondsMeasured" to false, "newEtaModelAccuracyProven" to false)
            Files.writeString(output.resolve("collection-report.json"), mapper.writeValueAsString(report), CREATE_NEW, WRITE)
            registry.close()
        }
        check(completed == cycles && failureCode == null) { "Collection incomplete; preserve evidence and do not retry provider errors" }
    }
}
