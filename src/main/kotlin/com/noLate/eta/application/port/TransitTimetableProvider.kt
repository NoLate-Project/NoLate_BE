package com.noLate.eta.application.port

import com.noLate.eta.domain.SeoulSubwayLine
import com.noLate.eta.domain.TransitLegTimetable
import com.noLate.eta.domain.TransitServiceClass
import java.time.Instant
import java.time.LocalDate

internal const val SEOUL_DATED_TIMETABLE_PROVIDER_ID = "seoul-dated-path"

/** 경로 공급자(Tmap/ODsay)와 운행 시간표 공급자를 분리한다. */
interface TransitTimetableProvider {
    fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable>
}

/** Returns one date-specific ride starting at or after the supplied station-ready time. */
fun interface DatedTransitTimetableProvider {
    fun findRide(query: TransitTimetableQuery, orderedStopNames: List<String>): TransitLegTimetable?
}

data class TransitTimetableQuery(
    val line: SeoulSubwayLine,
    val fromName: String,
    val toName: String,
    val nextStopName: String?,
    val serviceClass: TransitServiceClass,
    val notBefore: Instant,
    val directionCode: String? = null,
    /** 첫 승차 준비시각의 운행일. 자정 이후 환승에도 같은 운행일의 승하차역 행을 대조한다. */
    val serviceDate: LocalDate? = null,
)
