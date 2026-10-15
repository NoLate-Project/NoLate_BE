package com.noLate.eta.simulator

import com.noLate.eta.application.TransitEtaCalculationService
import com.noLate.eta.application.transit.FirstBoardingRealtimeOverlay
import com.noLate.eta.application.transit.TransitRealtimeOverlayResolution
import com.noLate.eta.domain.TransitFirstBoardingObservation
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitLegTimetable
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.TransitArrivalDto
import org.aopalliance.intercept.MethodInterceptor
import org.springframework.aop.framework.ProxyFactory
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant

/** 공개 노원 경로 격리 서버 전용. 실제 호출/반환 객체/예외를 그대로 전달하며 추가 HTTP는 없다. */
internal class RealFirstBoardingSelectionTrace(
    private val calculator: TransitEtaCalculationService,
    private val overlay: FirstBoardingRealtimeOverlay,
) : AutoCloseable {
    private val originalOverlay = ReflectionTestUtils.getField(calculator, "firstBoardingRealtimeOverlay")
    private val originalService = ReflectionTestUtils.getField(overlay, "transitArrivalService") as TransitArrivalService
    private val boardingBuffer = ReflectionTestUtils.getField(overlay, "boardingBufferSeconds") as Long
    private val events = mutableListOf<Map<String, Any?>>()
    private var omitted = 0

    init {
        check(originalOverlay === overlay)
        val serviceProxy = ProxyFactory(originalService).apply {
            isProxyTargetClass = true
            addAdvice(MethodInterceptor { invocation ->
                val result = invocation.proceed() // 원본 객체를 수정하거나 대역 응답으로 바꾸지 않는다.
                if (invocation.method.name == "getSubwayArrivalsForEta" && invocation.arguments[0] == "노원") {
                    @Suppress("UNCHECKED_CAST")
                    val arrivals = result as List<TransitArrivalDto>
                    record(mapOf("kind" to "ARRIVALS", "observedAt" to Instant.now().toString(),
                        "lineName" to invocation.arguments[1], "directionName" to invocation.arguments[2],
                        "directionCode" to invocation.arguments[3], "limit" to invocation.arguments[4],
                        "vehicles" to arrivals.take(10).map { a -> mapOf(
                            "trainNumber" to a.trainNumber?.take(32), "terminal" to a.destinationName?.take(100),
                            "direction" to a.direction?.take(100), "express" to a.express,
                            "sourceUpdatedAt" to a.sourceUpdatedAt, "expectedAt" to a.expectedAt,
                            "freshnessEvidence" to a.freshnessEvidence.name, "realtime" to a.realtime,
                        ) }))
                }
                result
            })
        }.proxy
        val overlayProxy = ProxyFactory(overlay).apply {
            isProxyTargetClass = true
            addAdvice(MethodInterceptor { invocation ->
                val result = invocation.proceed()
                val journey = invocation.arguments.firstOrNull() as? TransitJourney
                if (invocation.method.name in setOf("resolve", "observeTimetableCandidate") &&
                    journey?.rideLegs?.firstOrNull()?.from?.name == "노원") {
                    val table = if (invocation.method.name == "resolve") journey.rideLegs[0].timetable
                        else invocation.arguments[1] as? TransitLegTimetable
                    val evaluatedAt = invocation.arguments[if (invocation.method.name == "resolve") 1 else 2] as Instant
                    val access = journey.legs.takeWhile { !it.isRide }.sumOf { it.movementDurationSeconds }
                    record(mapOf("kind" to "SELECTION", "method" to invocation.method.name,
                        "evaluatedAt" to evaluatedAt.toString(), "departureAt" to journey.departureAt.toString(),
                        "readyAt" to maxOf(evaluatedAt, journey.departureAt).plusSeconds(access + boardingBuffer).toString(),
                        "expectedTrainNumber" to table?.trainNumber, "realtimeTrainNumber" to table?.realtimeTrainNumber,
                        "expectedTerminal" to table?.terminalName, "scheduledBoardingAt" to table?.boardingAt?.toString(),
                        "failureCode" to (result as? TransitRealtimeOverlayResolution)?.failureReason?.substringBefore(':'),
                        "observationAccepted" to (result is TransitFirstBoardingObservation ||
                            (result as? TransitRealtimeOverlayResolution)?.firstBoardingObservation != null)))
                }
                result
            })
        }.proxy
        ReflectionTestUtils.setField(overlay, "transitArrivalService", serviceProxy)
        ReflectionTestUtils.setField(calculator, "firstBoardingRealtimeOverlay", overlayProxy)
    }

    private fun record(event: Map<String, Any?>) {
        if (events.size < 96) events += event else omitted++
    }

    fun snapshot(): Map<String, Any?> = mapOf(
        "classification" to "PASSIVE_PUBLIC_FIRST_BOARDING_SELECTION_TRACE",
        "additionalHttpCalls" to 0, "productionLogicChanged" to false,
        "events" to events.toList(), "omittedEvents" to omitted,
    )

    override fun close() {
        ReflectionTestUtils.setField(calculator, "firstBoardingRealtimeOverlay", originalOverlay)
        ReflectionTestUtils.setField(overlay, "transitArrivalService", originalService)
    }
}
