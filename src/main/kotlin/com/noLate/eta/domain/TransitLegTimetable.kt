package com.noLate.eta.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 서울 역 시간표의 운행일은 현지 03:00에 시작하며 자정 이후는 24~26시로 표기한다. */
internal object SeoulSubwayServiceDay {
    val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private const val START_HOUR = 3

    fun dateAt(at: Instant): LocalDate = at.atZone(zone).minusHours(START_HOUR.toLong()).toLocalDate()

    fun startsAt(date: LocalDate): Instant = date.atTime(START_HOUR, 0).atZone(zone).toInstant()

    // 새벽에는 진행 중인 전날 운행과 오늘 03시부터 시작할 운행을 각각 조회할 수 있다.
    fun hasCurrentStationTables(date: LocalDate, now: Instant): Boolean =
        date == now.atZone(zone).toLocalDate() || date == dateAt(now)
}

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
    fun isObservableServiceDay(departureAt: Instant, evaluatedAt: Instant): Boolean {
        val today = evaluatedAt.atZone(SeoulSubwayServiceDay.zone).toLocalDate()
        if (serviceDate == today) return true

        // The saved route can carry yesterday's explicit 24–26h timetable into today's early hours.
        // Never infer that a fresh row with a reused train number belongs to an old plan.
        return serviceDate == SeoulSubwayServiceDay.dateAt(evaluatedAt) &&
            serviceDate == SeoulSubwayServiceDay.dateAt(departureAt) &&
            serviceDate == SeoulSubwayServiceDay.dateAt(arrivalAt) &&
            arrivalAt.atZone(SeoulSubwayServiceDay.zone).toLocalDate() == today
    }

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
