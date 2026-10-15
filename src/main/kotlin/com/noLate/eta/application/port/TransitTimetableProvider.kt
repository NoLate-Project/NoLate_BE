package com.noLate.eta.application.port

import com.noLate.eta.domain.SeoulSubwayLine
import com.noLate.eta.domain.TransitLegTimetable
import com.noLate.eta.domain.TransitServiceClass
import java.time.Instant
import java.time.LocalDate

/** 경로 공급자(Tmap/ODsay)와 운행 시간표 공급자를 분리한다. */
interface TransitTimetableProvider {
    fun findRides(query: TransitTimetableQuery): List<TransitLegTimetable>
}

data class TransitTimetableQuery(
    val line: SeoulSubwayLine,
    val fromName: String,
    val toName: String,
    val nextStopName: String?,
    val serviceClass: TransitServiceClass,
    val notBefore: Instant,
    val directionCode: String? = null,
    /** 여정이 시작한 운행일. 자정 이후 환승을 다음 날의 다른 시간표와 섞지 않는다. */
    val serviceDate: LocalDate? = null,
)
