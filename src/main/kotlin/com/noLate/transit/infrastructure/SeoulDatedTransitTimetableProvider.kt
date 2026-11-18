package com.noLate.transit.infrastructure

import com.noLate.eta.application.port.DatedTransitTimetableProvider
import com.noLate.eta.application.port.SEOUL_DATED_TIMETABLE_PROVIDER_ID
import com.noLate.eta.application.port.TransitTimetableQuery
import com.noLate.eta.domain.TransitLegTimetable
import com.noLate.eta.domain.TransitServiceClass
import com.noLate.eta.resilience.EtaCalculationDeadline
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import jakarta.annotation.PreDestroy
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@Component
@ConditionalOnProperty(name = ["DATA_GO_KR_SUBWAY_PATH_API_KEY"])
internal class SeoulDatedTransitTimetableProvider(
    @Value("\${DATA_GO_KR_SUBWAY_PATH_API_KEY:}") serviceKey: String,
    private val clock: Clock,
    private val deadline: EtaCalculationDeadline,
) : DatedTransitTimetableProvider {
    private val client = serviceKey.takeIf(String::isNotBlank)?.let { SeoulMetroShortestPathClient(it) }

    override fun findRide(query: TransitTimetableQuery, orderedStopNames: List<String>): TransitLegTimetable? {
        val pathClient = client ?: return null
        val date = query.serviceDate ?: return null
        val ready = query.notBefore.atZone(SEOUL)
        if (ready.toLocalDate() != date || ready.hour < 3 ||
            query.serviceClass == TransitServiceClass.UNKNOWN ||
            orderedStopNames.size !in 2..512 ||
            orderedStopNames.first() != query.fromName || orderedStopNames.last() != query.toName ||
            orderedStopNames[1] != query.nextStopName) return null
        val limit = deadline.current() ?: return null
        val searchAt = ready.toLocalDateTime()
        val response = pathClient.fetchScheduled(
            SeoulMetroShortestPathClient.Query(query.fromName, query.toName, searchAt), limit.remaining(),
        )
        return bindRide(query, orderedStopNames, response, clock.instant())
    }

    @PreDestroy
    fun close() { client?.close() }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

/** Keep ODsay's route; the official response contributes only the times of an identical ride. */
internal fun bindRide(
    query: TransitTimetableQuery,
    orderedStopNames: List<String>,
    path: SeoulMetroShortestPath,
    fetchedAt: Instant,
): TransitLegTimetable? {
    val date = query.serviceDate ?: return null
    val sections = path.sections
    if (sections.isEmpty() || sections.size != orderedStopNames.size - 1) return null
    val stations = listOf(sections.first().departure) + sections.map { it.arrival }
    if (stations.any { it.line != query.line.displayName } ||
        stations.map { it.code }.distinct().size != stations.size ||
        stations.map { normalizeStationName(it.name) } != orderedStopNames.map(::normalizeStationName)) return null
    val first = sections.first()
    val direction = when (first.direction) {
        "상행", "내선" -> "UP"
        "하행", "외선" -> "DOWN"
        else -> return null
    }
    val express = when (query.serviceClass) {
        TransitServiceClass.LOCAL -> "N"
        TransitServiceClass.EXPRESS -> "Y"
        else -> return null
    }
    if (query.directionCode != null && query.directionCode != direction) return null
    if (first.terminalCode == null || sections.any {
            it.transfer != "N" || it.express != express || it.direction != first.direction ||
                it.terminalCode != first.terminalCode
        }) return null
    val schedule = sections.map { it.scheduled ?: return null }
    val boarded = schedule.first()
    if (boarded.departureTime.hour < 3 || schedule.any {
            it.trainNumber != boarded.trainNumber || it.terminalName != boarded.terminalName ||
                it.arrivalTime.seoulServiceDaySecond() <= it.departureTime.seoulServiceDaySecond()
        } || schedule.zipWithNext().any { (before, after) ->
            before.arrivalTime.seoulServiceDaySecond() > after.departureTime.seoulServiceDaySecond()
        }) return null
    val zone = ZoneId.of("Asia/Seoul")
    val midnight = date.atStartOfDay(zone).toInstant()
    val boardingAt = midnight.plusSeconds(boarded.departureTime.seoulServiceDaySecond())
    val arrivalAt = midnight.plusSeconds(schedule.last().arrivalTime.seoulServiceDaySecond())
    if (boardingAt < query.notBefore || arrivalAt > boardingAt.plusSeconds(4 * 60 * 60)) return null
    return TransitLegTimetable(
        SEOUL_DATED_TIMETABLE_PROVIDER_ID, query.line, date, boarded.trainNumber, direction, query.serviceClass,
        stations.first().code, stations.last().code, boarded.terminalName, boardingAt,
        arrivalAt, fetchedAt,
    )
}

private fun normalizeStationName(name: String): String =
    name.replace(Regex("""\([^)]*\)"""), "").filterNot(Char::isWhitespace).removeSuffix("역")
