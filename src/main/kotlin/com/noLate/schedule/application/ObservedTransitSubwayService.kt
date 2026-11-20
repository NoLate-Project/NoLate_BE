package com.noLate.schedule.application

import com.noLate.eta.domain.SeoulSubwayLine
import com.noLate.eta.domain.TransitServiceClass
import com.noLate.eta.domain.TransitLegTimetable
import java.time.LocalDate

/**
 * 승차역과 하차역이 같은 차량으로 직접 식별한 운행 편.
 * 비교용 시간표의 열차번호와 같다는 별칭 관계나 실제 승객의 탑승을 뜻하지 않는다.
 */
data class ObservedTransitSubwayService(
    val provider: String,
    val line: SeoulSubwayLine,
    val serviceDate: LocalDate,
    val trainNumber: String,
    val directionCode: String,
    val serviceClass: TransitServiceClass,
    val terminalName: String,
    val boardingStationName: String,
    val alightingStationName: String,
) {
    fun matchesReference(table: TransitLegTimetable): Boolean =
        line == table.line && serviceDate == table.serviceDate && directionCode == table.directionCode &&
            serviceClass == table.serviceClass && stationKey(terminalName) == stationKey(table.terminalName)

    fun matchesOrderedRide(from: String?, to: String?, stops: List<String>): Boolean =
        boardingStationName == from?.trim() && alightingStationName == to?.trim() && stops.size in 2..512 &&
            stops.all { stationKey(it) != null } && stops.map(::stationKey).distinct().size == stops.size &&
            stationKey(stops.first()) == stationKey(boardingStationName) &&
            stationKey(stops.last()) == stationKey(alightingStationName)

    init {
        require(provider == "seoul-openapi")
        require(trainNumber.matches(Regex("[A-Z]?[0-9]{1,12}")) && trainNumber.any { it in '1'..'9' })
        require(directionCode in setOf("UP", "DOWN"))
        require(serviceClass in setOf(TransitServiceClass.LOCAL, TransitServiceClass.EXPRESS))
        require(listOf(terminalName, boardingStationName, alightingStationName).all {
            it.isNotBlank() && it == it.trim() && it.length <= 100 && it.none(Char::isISOControl)
        })
        require(boardingStationName != alightingStationName)
    }

    private fun stationKey(value: String): String? = value.replace(Regex("\\([^)]*\\)"), "")
        .replace(Regex("\\s+"), "").removeSuffix("종착").removeSuffix("방면")
        .removeSuffix("행").removeSuffix("역").takeIf(String::isNotBlank)
}
