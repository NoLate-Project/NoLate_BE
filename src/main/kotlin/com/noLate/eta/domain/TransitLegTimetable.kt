package com.noLate.eta.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** 동일 열차번호·운행일·방향·종별로 승하차역을 대조한 시간표 증거. */
data class TransitLegTimetable(
    val provider: String,
    val line: SeoulSubwayLine,
    val serviceDate: LocalDate,
    val trainNumber: String,
    val directionCode: String,
    val serviceClass: TransitServiceClass,
    val fromStationCode: String,
    val toStationCode: String,
    val terminalName: String,
    val boardingAt: Instant,
    val arrivalAt: Instant,
    val fetchedAt: Instant,
    /** 서울 시간표 adapter가 중복 없는 4호선 K열차 표기를 대조한 실시간 번호. 원본은 보존한다. */
    val realtimeTrainNumber: String? = null,
    /** 승차역의 시간표상 열차 도착. boardingAt은 같은 역의 시간표상 출발이다. */
    val boardingStationArrivalAt: Instant? = null,
) {
    fun shiftFromObservedBoardingArrival(observedAt: Instant): Duration =
        Duration.between(boardingStationArrivalAt ?: boardingAt, observedAt)

    init {
        require(provider.isNotBlank() && trainNumber.isNotBlank() && terminalName.isNotBlank())
        require(fromStationCode.isNotBlank() && toStationCode.isNotBlank() && fromStationCode != toStationCode)
        require(directionCode == "UP" || directionCode == "DOWN")
        require(serviceClass != TransitServiceClass.UNKNOWN && arrivalAt.isAfter(boardingAt))
        require(boardingStationArrivalAt == null || boardingStationArrivalAt <= boardingAt)
        realtimeTrainNumber?.let {
            require(provider == "seoul-timetable" && line == SeoulSubwayLine.LINE_4 &&
                trainNumber.matches(Regex("K[0-9]{4}")) && it == trainNumber.substring(1))
        }
    }
}
