package com.noLate.schedule.application.service.policy

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.noLate.schedule.application.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class PlannedTransitRefreshCadencePolicyTest {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val route = requireNotNull(javaClass.getResourceAsStream("/eta/reported-boarding-arrival-20261120.json"))
        .use(mapper::readTree).let { mapper.treeToValue(it["plan"], EffectiveTransitRoute::class.java) }
    private val firstArrival = requireNotNull(route.segments.first { it.rideTiming != null }.rideTiming).predictedArrivalAt
    private fun state(plan: EffectiveTransitRoute = route) = EffectiveTransitRouteSnapshot(inProgressPlannedRoute = plan)

    @ParameterizedTest
    @CsvSource("600,60", "900,60", "901,1", "930,30", "1800,60", "1500,600")
    fun `가까운 역 사건과 관측 창 시작에만 조회 경계를 추가한다`(before: Long, delay: Long) {
        val now = firstArrival.minusSeconds(before)
        assertEquals(now.plusSeconds(delay), PlannedTransitRefreshCadencePolicy.nextRefreshAt(now,state()))
    }

    @Test
    fun `출발 전과 모든 역 사건의 지연 조회 창이 지난 뒤에는 기존 주기를 유지한다`() {
        assertNull(PlannedTransitRefreshCadencePolicy.nextRefreshAt(route.departureAt.minusSeconds(1),state()))
        val lastArrival = requireNotNull(route.segments.last { it.rideTiming != null }.rideTiming).predictedArrivalAt
        assertNull(PlannedTransitRefreshCadencePolicy.nextRefreshAt(lastArrival.plusSeconds(1201),state()))
        assertNull(PlannedTransitRefreshCadencePolicy.nextRefreshAt(firstArrival,null))
    }

    @Test
    fun `같은 여정의 갱신한 시각을 쓰고 다른 여정의 시각은 섞지 않는다`() {
        val now = firstArrival.minusSeconds(930)
        val delayed = route.copy(arrivalAt=route.arrivalAt.plusSeconds(300),segments=route.segments.map { leg ->
            leg.rideTiming?.let { timing -> leg.copy(rideTiming=timing.copy(
                predictedBoardingAt=timing.predictedBoardingAt.plusSeconds(300),
                predictedArrivalAt=timing.predictedArrivalAt.plusSeconds(300),
                basis=EffectiveTransitRideTimingBasis.PLANNED_RIDE_BASELINE)) } ?: leg
        })
        assertEquals(now.plusSeconds(330),PlannedTransitRefreshCadencePolicy.nextRefreshAt(now,
            state().copy(lastUpdatedPlannedRoute=delayed)))
        assertEquals(now.plusSeconds(30),PlannedTransitRefreshCadencePolicy.nextRefreshAt(now,
            state().copy(lastUpdatedPlannedRoute=delayed.copy(identity="b".repeat(64)))))
    }

    @Test
    fun `다른 운행일과 미지원 혼합 경로는 관측 주기로 승격하지 않는다`() {
        val now = firstArrival.minusSeconds(600)
        val otherDay = route.copy(segments=route.segments.map { leg -> leg.rideTiming?.let { timing ->
            leg.copy(rideTiming=timing.copy(timetable=timing.timetable.copy(serviceDate=timing.timetable.serviceDate.minusDays(1))))
        } ?: leg })
        assertNull(PlannedTransitRefreshCadencePolicy.nextRefreshAt(now,state(otherDay)))
        val missing = route.copy(segments=route.segments.map { if(it.rideTiming!=null) it.copy(rideTiming=null) else it })
        assertNull(PlannedTransitRefreshCadencePolicy.nextRefreshAt(now,state(missing)))
    }
}
