package com.noLate.eta.infrastructure.seoul

import com.fasterxml.jackson.databind.JsonNode
import com.noLate.eta.application.port.TransitTimetableProvider
import com.noLate.eta.application.port.TransitTimetableQuery
import com.noLate.eta.domain.*
import com.noLate.eta.resilience.*
import com.noLate.transit.infrastructure.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.*
import java.util.LinkedHashMap

/**
 * 서울교통공사 OA-121 역명 검색 + OA-101 역코드 시간표. 공급자 내부 ID는 서로 변환하지 않는다.
 * https://data.seoul.go.kr/dataList/OA-121/A/1/datasetView.do
 * https://data.seoul.go.kr/dataList/OA-101/A/1/datasetView.do
 * OA-101은 날짜별 유효기간을 제공하지 않으므로 현재 운행일만 연결한다. 장래 날짜/임시 운행은 별도 API가 필요하다.
 */
@Component
@ConditionalOnProperty(prefix = "eta.transit.seoul-timetable", name = ["enabled"], havingValue = "true")
class SeoulTransitTimetableProvider(
    @Value("\${eta.transit.seoul-timetable.api-key:}") private val apiKey: String,
    @Value("\${eta.transit.seoul-timetable.base-url:http://openapi.seoul.go.kr:8088}") baseUrl: String,
    private val serviceDay: SeoulTimetableServiceDay,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${eta.transit.seoul-timetable.allow-insecure-http:false}") allowInsecureHttp: Boolean = false,
    private val calculationDeadline: EtaCalculationDeadline = EtaCalculationDeadline(),
    private val guard: EtaProviderGuard = EtaProviderGuard(StaticEtaProviderResiliencePolicyResolver(), calculationDeadline),
    private val wireMetrics: TransitProviderWireMetrics? = null,
    private val wireRateLimiter: TransitProviderWireRateLimiter = TransitProviderWireRateLimiter(),
) : TransitTimetableProvider {
    private val zone = ZoneId.of("Asia/Seoul")
    private val cache = LinkedHashMap<String, CachedPayload>(16, 0.75f, true)
    private val http = RestClient.builder().baseUrl(baseUrl)
        .requestFactory(EtaDeadlineAwareClientHttpRequestFactory(calculationDeadline, Duration.ofSeconds(2), Duration.ofSeconds(4)))
        .build()

    init {
        require(apiKey.isNotBlank()) { "서울 시간표 활성화에는 일반 열린데이터 인증키가 필요합니다." }
        validateTransitProviderEndpoint(TransitWireProvider.SEOUL_TIMETABLE, baseUrl, apiKey.isNotBlank(), allowInsecureHttp)
    }

    override fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable> {
        if (apiKey.isBlank() || query.serviceClass == TransitServiceClass.UNKNOWN) return emptyList()
        if (query.line == SeoulSubwayLine.LINE_2 && query.directionCode == null && query.nextStopName.isNullOrBlank()) return emptyList()
        val requested = query.notBefore.atZone(zone)
        val date = query.serviceDate ?: requested.toLocalDate()
        // 오늘 출발한 여정의 환승은 자정을 넘을 수 있다. 명시적으로 전달된 운행일의
        // 24~26시 원본 행만 연결하며, 새벽 시각에서 전날 운행일을 임의 추정하지 않는다.
        if (date != clock.instant().atZone(zone).toLocalDate()) return emptyList()
        val sameDay = requested.toLocalDate() == date && requested.hour >= 3
        val explicitOvernight = query.serviceDate != null &&
            requested.toLocalDate() == date.plusDays(1) && requested.hour < 3
        if (!sameDay && !explicitOvernight) return emptyList()
        val weekTag = serviceDay.weekTag(date) ?: return emptyList()
        if (weekTag !in setOf("1", "2", "3")) return emptyList()
        return calculationDeadline.within {
            guard.execute(TransitWireProvider.SEOUL_TIMETABLE.guardId) {
                resolveRides(query, date, weekTag)
            }
        }
    }

    private fun resolveRides(query: TransitTimetableQuery, date: LocalDate, weekTag: String): List<TransitLegTimetable> {
        val from = stationCode(query.fromName, query.line) ?: return emptyList()
        val to = stationCode(query.toName, query.line) ?: return emptyList()
        if (from == to) return emptyList()
        val next = query.nextStopName?.let { stationCode(it, query.line) ?: return emptyList() }
        // 순환선은 양방향 모두 같은 역에 도달하므로 순서 증거 없이 방향을 추정하지 않는다.
        if (query.line == SeoulSubwayLine.LINE_2 && query.directionCode == null && (next == null || next == to)) return emptyList()
        val directions = when (query.directionCode) {
            "UP" -> listOf("1")
            "DOWN" -> listOf("2")
            null -> listOf("1", "2")
            else -> return emptyList()
        }
        val candidates = directions.flatMap { direction ->
            val board = table(from, query.fromName, query.line, date, weekTag, direction)
            val alightRows = table(to, query.toName, query.line, date, weekTag, direction)
            val alight = alightRows.groupBy(Row::identity)
            // 실제 4호선 응답: 시간표 K4401 / 실시간 4401. 다른 접두사는 일반화하지
            // 않으며 승하차 양쪽에서 숫자 표기가 하나의 원본 열차에만 대응할 때만 연결한다.
            fun realtimeNumber(train: String): String? {
                if (query.line != SeoulSubwayLine.LINE_4 || !train.matches(Regex("K[0-9]{4}"))) return null
                val numeric = train.substring(1)
                fun unique(rows: List<Row>) = rows.filter {
                    it.train == numeric || it.train == "K$numeric"
                }.map { it.train }.distinct() == listOf(train)
                return numeric.takeIf { unique(board) && unique(alightRows) }
            }
            val nextRows = next?.takeUnless { it == to }?.let {
                table(it, requireNotNull(query.nextStopName), query.line, date, weekTag, direction).groupBy(Row::identity)
            }
            board.groupBy(Row::identity).values.mapNotNull { sameRun ->
                // 같은 날 열차번호가 재사용되거나 중복이 모호하면 하나를 임의로 고르지 않는다.
                val start = sameRun.singleOrNull() ?: return@mapNotNull null
                val end = alight[start.identity]?.singleOrNull() ?: return@mapNotNull null
                val boardingAt = start.departure ?: return@mapNotNull null
                val arrivalAt = end.arrival ?: return@mapNotNull null
                if (start.serviceClass != query.serviceClass || boardingAt.isBefore(query.notBefore) ||
                    !arrivalAt.isAfter(boardingAt) || Duration.between(boardingAt, arrivalAt) > Duration.ofHours(4)) return@mapNotNull null
                if (nextRows != null) {
                    val nextArrival = nextRows[start.identity]?.singleOrNull()?.arrival ?: return@mapNotNull null
                    if (!nextArrival.isAfter(boardingAt) || !nextArrival.isBefore(arrivalAt)) return@mapNotNull null
                }
                TransitLegTimetable(
                    provider = "seoul-timetable", line = query.line, serviceDate = date, trainNumber = start.train,
                    directionCode = if (direction == "1") "UP" else "DOWN", serviceClass = start.serviceClass,
                    fromStationCode = from, toStationCode = to, terminalName = start.terminalName,
                    boardingAt = boardingAt, arrivalAt = arrivalAt, fetchedAt = minOf(start.fetchedAt, end.fetchedAt),
                    realtimeTrainNumber = realtimeNumber(start.train),
                )
            }
        }
        if (query.directionCode == null && candidates.map { it.directionCode }.distinct().size > 1) return emptyList()
        return candidates.sortedBy { it.boardingAt }
    }

    private fun stationCode(name: String, line: SeoulSubwayLine): String? {
        val stationName = normalizedStation(name) ?: return null
        val payload = payload("SearchInfoBySubwayNameService", listOf(stationName), null, TransitWireOperation.STATION_LOOKUP)
        return payload.rows.filter { lineMatches(it.text("LINE_NUM"), line) && normalizedStation(it.text("STATION_NM")) == stationName }
            .mapNotNull { it.text("STATION_CD")?.takeIf(CODE::matches) }.distinct().singleOrNull()
    }

    private fun table(code: String, name: String, line: SeoulSubwayLine, date: LocalDate, week: String, direction: String): List<Row> {
        val payload = payload("SearchSTNTimeTableByIDService", listOf(code, week, direction), date, TransitWireOperation.TIMETABLE)
        return payload.rows.mapNotNull { node ->
            if (node.text("STATION_CD") != code || normalizedStation(node.text("STATION_NM")) != normalizedStation(name) ||
                !lineMatches(node.text("LINE_NUM"), line) || node.text("WEEK_TAG") != week || node.text("INOUT_TAG") != direction) return@mapNotNull null
            val train = node.text("TRAIN_NO")?.takeIf(CODE::matches) ?: return@mapNotNull null
            val origin = node.text("ORIGINSTATION")?.takeIf(CODE::matches) ?: return@mapNotNull null
            val terminal = node.text("DESTSTATION")?.takeIf(CODE::matches) ?: return@mapNotNull null
            val terminalName = node.text("SUBWAYENAME")?.takeIf { it.length <= 100 } ?: return@mapNotNull null
            val service = when (node.text("EXPRESS_YN")) {
                "G" -> TransitServiceClass.LOCAL
                "D" -> TransitServiceClass.EXPRESS
                else -> return@mapNotNull null
            }
            Row(train, listOf(train, origin, terminal, node.text("BRANCH_LINE").orEmpty(), service.name, terminalName),
                terminalName, service, parseTime(node.text("ARRIVETIME"), date), parseTime(node.text("LEFTTIME"), date), payload.fetchedAt)
        }.distinct()
    }

    private fun payload(service: String, args: List<String>, date: LocalDate?, operation: TransitWireOperation): CachedPayload {
        val cacheKey = listOf(service, date?.toString().orEmpty()) + args
        val key = cacheKey.joinToString("|")
        synchronized(cache) { cache[key]?.takeIf { clock.instant().isBefore(it.fetchedAt.plusSeconds(900)) }?.let { return it } }
        val call = {
            wireRateLimiter.requirePermit(TransitWireProvider.SEOUL_TIMETABLE)
            val placeholders = args.indices.joinToString("/") { "{arg$it}" }
            val response = http.get().uri("/{key}/json/{service}/1/1000/$placeholders", apiKey, service, *args.toTypedArray())
                .retrieve().body(JsonNode::class.java) ?: throw IllegalStateException("서울 시간표 응답이 없습니다.")
            parsePayload(response, service, clock.instant())
        }
        val fetched = wireMetrics?.observe(TransitWireProvider.SEOUL_TIMETABLE, operation, { it.rows.isEmpty() }, call) ?: call()
        synchronized(cache) {
            if (cache.size >= 128) cache.entries.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
            cache[key] = fetched
        }
        return fetched
    }

    internal fun parsePayload(response: JsonNode, service: String, fetchedAt: Instant): CachedPayload {
        val body = response.path(service)
        val status = body.path("RESULT").text("CODE") ?: response.path("RESULT").text("CODE")
        if (status == "INFO-200") return CachedPayload(emptyList(), fetchedAt)
        if (status != "INFO-000") throw TransitProviderApplicationException(TransitWireProvider.SEOUL_TIMETABLE, status)
        val rows = body.path("row")
        val total = body.path("list_total_count")
        // 일부 페이지만 받은 응답을 전체 시간표로 간주하지 않는다. 상한 초과·불일치는 거절한다.
        if (!total.isIntegralNumber || total.asLong() !in 0..1000 || !rows.isArray || rows.size().toLong() != total.asLong()) {
            throw IllegalStateException("서울 시간표 전체 건수와 응답 행이 일치하지 않습니다.")
        }
        return CachedPayload(rows.toList(), fetchedAt)
    }

    internal fun parseTime(value: String?, date: LocalDate): Instant? {
        val match = value?.let(TIME::matchEntire) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        val second = match.groupValues[3].toInt()
        if (hour !in 3..26 || minute !in 0..59 || second !in 0..59) return null
        return date.atStartOfDay(zone).toInstant().plusSeconds(hour * 3600L + minute * 60L + second)
    }

    private fun normalizedStation(value: String?): String? = value?.replace(Regex("""\([^)]*\)"""), "")
        ?.replace(Regex("""\s+"""), "")?.removeSuffix("역")?.takeIf { it.isNotBlank() && it.length <= 100 }
    private fun lineMatches(value: String?, line: SeoulSubwayLine) =
        value?.replace(Regex("""\s+"""), "")?.removePrefix("0") == line.displayName
    private fun JsonNode.text(name: String): String? = path(name).takeIf { it.isTextual || it.isIntegralNumber }
        ?.asText()?.trim()?.takeIf(String::isNotBlank)
    internal data class CachedPayload(val rows: List<JsonNode>, val fetchedAt: Instant)
    private data class Row(val train: String, val identity: List<String>, val terminalName: String,
        val serviceClass: TransitServiceClass, val arrival: Instant?, val departure: Instant?, val fetchedAt: Instant)
    private companion object {
        val CODE = Regex("[A-Za-z0-9_-]{1,32}")
        val TIME = Regex("""(\d{2}):(\d{2}):(\d{2})""")
    }
}
