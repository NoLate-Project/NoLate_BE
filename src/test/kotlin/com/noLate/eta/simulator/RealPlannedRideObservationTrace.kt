package com.noLate.eta.simulator

import com.noLate.eta.application.transit.TransitTransferRealtimeGuard
import com.noLate.transit.application.TransitArrivalService
import com.noLate.transit.domain.TransitArrivalDto
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import org.aopalliance.intercept.MethodInterceptor
import org.springframework.aop.framework.ProxyFactory
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant

/** 격리 E2E 전용. 마지막 환승편 조회의 반환값만 기록하며 공급자 요청을 추가하지 않는다. */
internal class RealPlannedRideObservationTrace(private val guard: TransitTransferRealtimeGuard) : AutoCloseable {
    private val originalService = ReflectionTestUtils.getField(guard, "arrivals") as TransitArrivalService
    private val seoulClient = runCatching {
        ReflectionTestUtils.getField(originalService, "seoulTransitArrivalClient") as? SeoulTransitArrivalClient
    }.getOrNull()
    private val events = mutableListOf<Map<String, Any?>>()
    private var omitted = 0

    init {
        val proxy = ProxyFactory(originalService).apply {
            isProxyTargetClass = true
            addAdvice(MethodInterceptor { invocation ->
                if (invocation.method.name != "getSubwayArrivalsForEta") return@MethodInterceptor invocation.proceed()
                val requestedAt = Instant.now().toString()
                try {
                    @Suppress("UNCHECKED_CAST")
                    val rows = invocation.proceed() as List<TransitArrivalDto>
                    val stationName = invocation.arguments[0] as String
                    val lineName = invocation.arguments[1] as String?
                    // 기존 요청이 채운 역별 캐시만 확인한다. 방향 필터 또는 상위 10편에서
                    // 대상 열차가 빠졌는지 구분하되 두 번째 공급자 호출은 하지 않는다.
                    val rawLineRows = seoulClient?.peekCachedSubwayLineArrivals(stationName, lineName)
                    record(mapOf(
                        "requestedAt" to requestedAt,
                        "stationName" to stationName,
                        "lineName" to lineName,
                        "directionCode" to invocation.arguments[3],
                        "resultCount" to rows.size,
                        "rows" to rows.take(10).map(::summarize),
                        "rawCacheStatus" to if (rawLineRows == null) "UNAVAILABLE" else "AVAILABLE",
                        "rawLineResultCount" to rawLineRows?.size,
                        "rawLineRows" to rawLineRows?.take(40)?.map(::summarize),
                    ))
                    rows
                } catch (failure: Exception) {
                    record(mapOf("requestedAt" to requestedAt,
                        "stationName" to invocation.arguments[0],
                        "lineName" to invocation.arguments[1],
                        "directionCode" to invocation.arguments[3],
                        "failureKind" to failure::class.java.simpleName))
                    throw failure
                }
            })
        }.proxy
        ReflectionTestUtils.setField(guard, "arrivals", proxy)
    }

    private fun summarize(row: TransitArrivalDto): Map<String, Any?> = mapOf(
        "provider" to row.provider,
        "kind" to row.kind,
        "realtime" to row.realtime,
        "lineId" to row.subwayLineId,
        "stationName" to row.stationName?.take(100),
        "trainNumber" to row.trainNumber?.take(32),
        "terminal" to row.destinationName?.take(100),
        "direction" to row.direction?.take(100),
        "express" to row.express,
        "sourceUpdatedAt" to row.sourceUpdatedAt,
        "expectedAt" to row.expectedAt,
        "waitSeconds" to row.waitSeconds,
        "freshnessEvidence" to row.freshnessEvidence.name,
        "arrivalStatus" to row.arrivalStatus.name,
    )

    private fun record(event: Map<String, Any?>) {
        if (events.size < 96) events += event else omitted++
    }

    fun snapshot(): Map<String, Any?> = mapOf(
        "classification" to "PASSIVE_PLANNED_RIDE_ARRIVAL_TRACE",
        "additionalHttpCalls" to 0,
        "productionLogicChanged" to false,
        "events" to events.toList(),
        "omittedEvents" to omitted,
    )

    override fun close() {
        ReflectionTestUtils.setField(guard, "arrivals", originalService)
    }
}
