package com.noLate.eta.simulator

import com.noLate.schedule.application.TrafficClient
import com.noLate.schedule.application.TrafficRequest
import com.noLate.schedule.application.TrafficResult
import com.noLate.schedule.application.service.SchedulePushJobWorker
import org.aopalliance.intercept.MethodInterceptor
import org.springframework.aop.framework.ProxyFactory
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant

/** Test-classpath witness only: preserves the original request, response and HTTP count. */
internal class RealFutureTimetablePlanTrace(private val worker: SchedulePushJobWorker) : AutoCloseable {
    private val original = ReflectionTestUtils.getField(worker, "trafficClient") as TrafficClient
    private val events = mutableListOf<Map<String, Any?>>()
    init {
        val proxy = ProxyFactory().apply {
            setTarget(original)
            setInterfaces(TrafficClient::class.java)
            addAdvice(MethodInterceptor { invocation ->
                val result = invocation.proceed()
                if (invocation.method.name == "getTravelMinutes") {
                    val request = invocation.arguments.single() as TrafficRequest
                    val value = result as TrafficResult
                    synchronized(events) {
                        if (events.size < 3) events += mapOf(
                            "evaluatedAt" to request.evaluatedAt, "plannedDepartureAt" to request.plannedDepartureAt,
                            "targetArrivalAt" to request.targetArrivalAt, "returnedAt" to Instant.now(),
                            "source" to value.source, "stale" to value.stale, "failureReason" to value.failureReason,
                            "result" to value,
                            "futureTimetablePlanEvidence" to value.futureTimetablePlanEvidence)
                    }
                }
                result
            })
        }.proxy as TrafficClient
        ReflectionTestUtils.setField(worker, "trafficClient", proxy)
    }
    fun snapshot() = synchronized(events) { mapOf("classification" to "PASSIVE_REAL_FUTURE_TIMETABLE_PLAN_TRACE",
        "additionalHttpCalls" to 0, "responseModified" to false, "events" to events.toList()) }
    override fun close() { ReflectionTestUtils.setField(worker, "trafficClient", original) }
}
