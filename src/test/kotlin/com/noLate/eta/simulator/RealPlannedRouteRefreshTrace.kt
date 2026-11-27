package com.noLate.eta.simulator

import com.noLate.eta.application.port.TransitTimetableQuery
import com.noLate.eta.application.transit.PlannedTransitRouteRefresher
import com.noLate.eta.domain.TransitLegTimetable
import com.noLate.schedule.application.EffectiveTransitRoute
import com.noLate.schedule.application.PlannedTransitRouteUpdate
import com.noLate.schedule.application.service.SchedulePushJobWorker
import com.noLate.transit.domain.TransitArrivalDto
import org.aopalliance.intercept.MethodInterceptor
import org.springframework.aop.framework.ProxyFactory
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant

/** Capture only calls made inside one actual refresh. Other lookups in the Worker or app are independent. */
internal class RealPlannedRouteRefreshTrace(private val worker: SchedulePushJobWorker) : AutoCloseable {
    data class StationRead(val station: String, val line: String?, val directionName: String?, val directionCode: String?,
                           val limit: Int, val rows: List<TransitArrivalDto>)
    data class TableRead(val providerIndex: Int, val query: TransitTimetableQuery, val rides: List<TransitLegTimetable>)
    data class Frame(
        val evaluatedAt: Instant, val plan: EffectiveTransitRoute, val departurePlan: EffectiveTransitRoute,
        val previousUpdate: PlannedTransitRouteUpdate?, val stations: MutableList<StationRead> = mutableListOf(),
        val timetables: MutableList<TableRead> = mutableListOf(), var incomplete: Boolean = false,
        var result: PlannedTransitRouteUpdate? = null,
    )

    private val original = ReflectionTestUtils.getField(worker, "plannedTransitRouteRefresher") as PlannedTransitRouteRefresher
    private val current = ThreadLocal<Frame>()
    private val frames = mutableListOf<Frame>()

    init {
        val proxy = ProxyFactory(original).apply {
            isProxyTargetClass = true
            addAdvice(MethodInterceptor { invocation ->
                val args = invocation.arguments
                // Kotlin calls the five-argument method even when endsAtTransfer defaults to false.
                // This replay only models complete subway plans, so omit transfer-prefix refreshes.
                if (invocation.method.name != "refresh" || args.size !in 4..5 || args.getOrNull(4) == true) {
                    return@MethodInterceptor invocation.proceed()
                }
                val frame = Frame(args[1] as Instant, args[0] as EffectiveTransitRoute, args[2] as EffectiveTransitRoute,
                    args[3] as PlannedTransitRouteUpdate?)
                current.set(frame)
                try {
                    (invocation.proceed() as PlannedTransitRouteUpdate?).also { frame.result = it }
                } catch (failure: Throwable) {
                    frame.incomplete = true
                    throw failure
                } finally {
                    current.remove()
                    synchronized(frames) { frames.add(frame) }
                }
            })
        }.proxy
        ReflectionTestUtils.setField(worker, "plannedTransitRouteRefresher", proxy)
    }

    fun station(args: Array<out Any?>, rows: List<TransitArrivalDto>) {
        val frame = current.get() ?: return
        if (rows.size > 10 || frame.stations.size >= 96) frame.incomplete = true
        else frame.stations += StationRead(args[0] as String, args[1] as String?, args[2] as String?,
            args[3] as String?, args[4] as Int, rows.toList())
    }

    fun timetable(index: Int, query: TransitTimetableQuery, rides: List<TransitLegTimetable>) {
        val frame = current.get() ?: return
        if (rides.size > 256 || frame.timetables.size >= 96) frame.incomplete = true
        else frame.timetables += TableRead(index, query, rides.toList())
    }

    fun incomplete() { current.get()?.incomplete = true }

    fun snapshot() = synchronized(frames) { frames.toList() }

    override fun close() { ReflectionTestUtils.setField(worker, "plannedTransitRouteRefresher", original) }
}
