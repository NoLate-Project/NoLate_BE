package com.noLate.eta.simulator

import com.noLate.eta.application.TransitEtaCalculationService
import com.noLate.eta.application.transit.TransitJourneyTimetableEnricher
import com.noLate.eta.domain.TransitJourney
import org.aopalliance.intercept.MethodInterceptor
import org.springframework.aop.framework.ProxyFactory
import org.springframework.test.util.ReflectionTestUtils

/** Same-query comparison evidence only. No additional HTTP, output replacement or production hook. */
internal class RealJourneyTimingTrace(private val calculator: TransitEtaCalculationService) : AutoCloseable {
    private val original = ReflectionTestUtils.getField(calculator, "timetableEnricher") as TransitJourneyTimetableEnricher
    private val events = mutableListOf<Map<String, Any?>>()

    init {
        val proxy = ProxyFactory(original).apply {
            isProxyTargetClass = true
            addAdvice(MethodInterceptor { invocation ->
                val result = invocation.proceed()
                if (invocation.method.name in setOf("enrich", "reconnectTransfers", "reconnectEarlierTransfers") && result is TransitJourney && events.size < 48) {
                    val input = invocation.arguments[0] as TransitJourney
                    events += mapOf(
                        "method" to invocation.method.name,
                        "requestedDepartureAt" to input.requestedDepartureAt,
                        "departureAt" to result.departureAt, "arrivalAt" to result.arrivalAt,
                        "provider" to result.provider, "totalMinutes" to result.totalMinutes,
                        // Keep durations, train identities and original vs predicted clocks distinct.
                        // Coordinates, raw response bodies and credentials are deliberately omitted.
                        "legs" to result.legs.mapIndexed { index, leg -> mapOf(
                            "mode" to leg.mode, "walkingScope" to result.walkingScopeAt(index),
                            "movementSeconds" to if (!leg.isRide) leg.movementDurationSeconds else null,
                            "from" to leg.from?.name, "to" to leg.to?.name,
                            "timingBasis" to leg.timingBasis, "timetable" to leg.timetable,
                            "scheduledDepartureAt" to leg.scheduledDepartureAt,
                            "scheduledArrivalAt" to leg.scheduledArrivalAt,
                            "firstBoardingObservation" to leg.firstBoardingObservation,
                        ) },
                    )
                }
                result // Preserve the exact original returned object.
            })
        }.proxy
        ReflectionTestUtils.setField(calculator, "timetableEnricher", proxy)
    }

    fun snapshot() = mapOf("classification" to "PASSIVE_REAL_JOURNEY_TIMING_TRACE",
        "additionalHttpCalls" to 0, "responseModified" to false, "events" to events.toList())

    override fun close() { ReflectionTestUtils.setField(calculator, "timetableEnricher", original) }
}
