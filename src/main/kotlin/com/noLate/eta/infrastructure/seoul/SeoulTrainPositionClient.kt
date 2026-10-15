package com.noLate.eta.infrastructure.seoul

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.eta.application.port.TransitTrainPositionProvider
import com.noLate.eta.domain.*
import com.noLate.eta.resilience.*
import com.noLate.transit.infrastructure.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.*
import java.time.format.DateTimeFormatter

/** OA-12601. No scheduled poll, key rotation, retry or user-specific request is performed here. */
@Component
class SeoulTrainPositionClient(
    @Value("\${transit.seoul.api-key:}") commonApiKey: String,
    @Value("\${transit.seoul.subway-api-key:}") subwayApiKey: String,
    @Value("\${transit.seoul.subway-base-url:http://swopenAPI.seoul.go.kr/api/subway}") baseUrl: String,
    @Value("\${transit.seoul.allow-insecure-http:false}") allowInsecureHttp: Boolean = false,
    private val clock: Clock = Clock.systemUTC(),
    private val calculationDeadline: EtaCalculationDeadline = EtaCalculationDeadline(),
    private val guard: EtaProviderGuard = EtaProviderGuard(StaticEtaProviderResiliencePolicyResolver(), calculationDeadline),
    private val wireMetrics: TransitProviderWireMetrics? = null,
    private val wireRateLimiter: TransitProviderWireRateLimiter = TransitProviderWireRateLimiter(),
    @Value("\${eta.transit.train-positions.max-daily-calls:120}") private val maxDailyCalls: Int = 120,
    @Value("\${eta.transit.train-positions.cache-seconds:60}") private val cacheSeconds: Long = 60,
) : TransitTrainPositionProvider {
    private val apiKey = subwayApiKey.ifBlank { commonApiKey }
    private val zone = ZoneId.of("Asia/Seoul")
    private val http = RestClient.builder().baseUrl(baseUrl)
        .requestFactory(EtaDeadlineAwareClientHttpRequestFactory(calculationDeadline, Duration.ofSeconds(2), Duration.ofSeconds(4)))
        .build()
    private val cache = mutableMapOf<SeoulSubwayLine, TransitTrainPositionSnapshot>()
    private var budgetDay: LocalDate? = null
    private var calls = 0
    private var blockedDay: LocalDate? = null

    init {
        require(maxDailyCalls in 0..1000 && cacheSeconds in 30..300)
        validateTransitProviderEndpoint(TransitWireProvider.SEOUL_SUBWAY, baseUrl, apiKey.isNotBlank(), allowInsecureHttp)
    }

    // At most nine lines; this single-flight lock also serializes the process-local budget.
    // An account-wide distributed budget is still required for multiple replicas.
    @Synchronized override fun positions(line: SeoulSubwayLine): TransitTrainPositionSnapshot {
        val now = clock.instant()
        val day = now.atZone(zone).toLocalDate()
        val previousDay = budgetDay
        if (previousDay == null || day > previousDay) { budgetDay = day; calls = 0 }
        check(budgetDay == day) { "Train position clock moved to an older budget day" }
        check(blockedDay != day) { "Train position collection stopped after provider failure" }
        cache[line]?.takeIf { now >= it.receivedAt && now < it.receivedAt.plusSeconds(cacheSeconds) }?.let { return it }
        check(apiKey.isNotBlank()) { "Train position credential is not configured" }
        check(calls < maxDailyCalls) { "Train position daily request budget exhausted" }
        calls++ // Includes failures; neither a fresh JVM nor this limit represents the account's actual remaining quota.
        try {
            return calculationDeadline.within {
                guard.execute(TransitWireProvider.SEOUL_SUBWAY.guardId) {
                    val call = {
                        wireRateLimiter.requirePermit(TransitWireProvider.SEOUL_SUBWAY)
                        val body = http.get().uri("/{key}/json/realtimePosition/0/1000/{line}", apiKey, line.displayName)
                            .retrieve().body(JsonNode::class.java) ?: error("Empty train position response")
                        parse(body, line, clock.instant())
                    }
                    (wireMetrics?.observe(TransitWireProvider.SEOUL_SUBWAY, TransitWireOperation.POSITION,
                        { it.positions.isEmpty() }, call) ?: call()).also { cache[line] = it }
                }
            }
        } catch (failure: RuntimeException) {
            blockedDay = day // Conservative collector: stop, never loop through quota/auth/transport failures.
            throw failure
        }
    }

    internal fun parse(body: JsonNode, line: SeoulSubwayLine, receivedAt: Instant): TransitTrainPositionSnapshot {
        val code = body.path("RESULT").path("CODE").asText(null)
            ?: body.path("errorMessage").path("code").asText(null) ?: body.path("code").asText(null)
        if (code == "INFO-200") return TransitTrainPositionSnapshot(line, receivedAt, emptyList())
        if (code != "INFO-000") throw TransitProviderApplicationException(TransitWireProvider.SEOUL_SUBWAY, code)
        val rows = body.path("realtimePositionList")
        val total = body.path("errorMessage").path("total").takeUnless { it.isMissingNode }
            ?: body.path("list_total_count")
        check(rows.isArray && rows.size() <= 1000 && total.asInt(-1) == rows.size()) { "Incomplete train position page" }
        val parsed = rows.mapNotNull { row -> runCatching {
            fun field(key: String) = row.path(key).asText("").trim()
            check(field("subwayId") == line.realtimeId)
            val dateText = field("lastRecptnDt")
            val date = when {
                dateText.matches(Regex("[0-9]{8}")) -> LocalDate.parse(dateText, DateTimeFormatter.BASIC_ISO_DATE)
                dateText.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) -> LocalDate.parse(dateText)
                else -> LocalDateTime.parse(dateText.replace(' ', 'T')).toLocalDate()
            }
            // A missing provider date/time is not replaced with the receipt clock.
            val timeText = field("recptnDt")
            val time = if (timeText.contains(' ') || timeText.contains('T')) {
                LocalDateTime.parse(timeText.replace(' ', 'T')).also { check(it.toLocalDate() == date) }.toLocalTime()
            } else LocalTime.parse(timeText)
            val timestamp = LocalDateTime.of(date, time).atZone(zone).toInstant()
            TransitTrainPosition(line, field("trainNo"), field("statnNm"),
                when (field("updnLine")) { "0" -> "UP"; "1" -> "DOWN"; else -> error("Unknown direction") },
                field("statnTnm"),
                when (field("directAt")) { "0" -> TransitServiceClass.LOCAL; "1", "7" -> TransitServiceClass.EXPRESS; else -> TransitServiceClass.UNKNOWN },
                when (field("trainSttus")) { "0" -> TransitTrainPositionState.ENTERING; "1" -> TransitTrainPositionState.ARRIVED;
                    "2" -> TransitTrainPositionState.DEPARTED; "3" -> TransitTrainPositionState.PREVIOUS_STATION_DEPARTED;
                    else -> error("Unknown train status") }, timestamp, receivedAt)
        }.getOrNull() }
        return TransitTrainPositionSnapshot(line, receivedAt, parsed, rows.size() - parsed.size)
    }
}
