package com.noLate.eta.simulator

import com.noLate.eta.application.port.TransitTimetableProvider
import com.noLate.eta.application.port.TransitTimetableQuery
import com.noLate.eta.application.transit.TransitJourneyTimetableEnricher
import com.noLate.eta.domain.TransitLegTimetable
import org.aopalliance.intercept.MethodInterceptor
import org.springframework.aop.framework.ProxyFactory
import org.springframework.test.util.ReflectionTestUtils

/** 공개 역 격리 검증 전용. 실제 조회 반환을 관찰하며 추가 호출·응답 수정은 하지 않는다. */
internal class RealTimetableSelectionTrace(private val enricher: TransitJourneyTimetableEnricher) : AutoCloseable {
    @Suppress("UNCHECKED_CAST")
    private val originals = ReflectionTestUtils.getField(enricher, "providers") as List<TransitTimetableProvider>
    private val events = mutableListOf<Map<String, Any?>>()

    init {
        val proxies = originals.map { original ->
            ProxyFactory(original).apply {
                addAdvice(MethodInterceptor { invocation ->
                    val result = invocation.proceed()
                    if (invocation.method.name == "findRides" && events.size < 96) {
                        val query = invocation.arguments[0] as TransitTimetableQuery
                        @Suppress("UNCHECKED_CAST")
                        val rides = result as List<TransitLegTimetable>
                        events += mapOf("query" to query, "count" to rides.size, "rides" to rides.take(6))
                    }
                    result
                })
            }.proxy as TransitTimetableProvider
        }
        ReflectionTestUtils.setField(enricher, "providers", proxies)
    }

    fun snapshot() = mapOf("classification" to "PASSIVE_REAL_TIMETABLE_QUERY_TRACE",
        "additionalHttpCalls" to 0, "responseModified" to false, "events" to events.toList())

    override fun close() { ReflectionTestUtils.setField(enricher, "providers", originals) }
}
