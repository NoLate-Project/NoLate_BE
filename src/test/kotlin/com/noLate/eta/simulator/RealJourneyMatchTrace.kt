package com.noLate.eta.simulator

import com.noLate.eta.application.TransitEtaCalculationService
import com.noLate.eta.application.transit.TransitJourneyMatcher
import com.noLate.eta.domain.SelectedTransitRoute
import com.noLate.eta.domain.TransitJourney
import com.noLate.eta.domain.TransitRideSignature
import org.aopalliance.intercept.MethodInterceptor
import org.springframework.aop.framework.ProxyFactory
import org.springframework.test.util.ReflectionTestUtils

/** Records the same inputs seen by the real matcher, including rejected routes. */
internal class RealJourneyMatchTrace(private val calculator: TransitEtaCalculationService) : AutoCloseable {
    private val original = ReflectionTestUtils.getField(calculator, "journeyMatcher") as TransitJourneyMatcher
    private val searches = mutableListOf<Map<String, Any?>>()

    init {
        val proxy = ProxyFactory(original).apply {
            isProxyTargetClass = true
            addAdvice(MethodInterceptor { invocation ->
                val result = invocation.proceed()
                if (invocation.method.name == "findSelectedCandidates" && searches.size < 12) {
                    val selected = invocation.arguments[0] as SelectedTransitRoute
                    @Suppress("UNCHECKED_CAST")
                    val candidates = invocation.arguments[1] as List<TransitJourney>
                    val matches = result as List<*>
                    searches += mapOf(
                        "provider" to selected.provider,
                        "selectedRides" to selected.rides.map(::signature),
                        "candidateCount" to candidates.size,
                        "matchedCount" to matches.size,
                        "candidates" to candidates.take(12).map { candidate ->
                            mapOf("matched" to (candidate in matches), "provider" to candidate.provider,
                                "rides" to candidate.rideLegs.map { leg ->
                                    signature(TransitRideSignature(leg.mode, leg.line?.providerRouteId, leg.line?.name,
                                        leg.line?.serviceClass ?: com.noLate.eta.domain.TransitServiceClass.UNKNOWN,
                                        leg.from?.stableIds(candidate.provider).orEmpty(), leg.from?.name,
                                        leg.to?.stableIds(candidate.provider).orEmpty(), leg.to?.name,
                                        leg.directionName, leg.directionCode, leg.orderedStopIds))
                                })
                        },
                    )
                }
                result
            })
        }.proxy
        ReflectionTestUtils.setField(calculator, "journeyMatcher", proxy)
    }

    private fun signature(ride: TransitRideSignature) = mapOf(
        "mode" to ride.mode, "lineId" to ride.providerRouteId, "line" to ride.lineName,
        "serviceClass" to ride.serviceClass, "fromIds" to ride.fromIds, "from" to ride.fromName,
        "toIds" to ride.toIds, "to" to ride.toName,
        "directionCode" to ride.directionCode, "directionName" to ride.directionName,
        "orderedStopIds" to ride.orderedStopIds,
    )

    fun snapshot() = mapOf("classification" to "PASSIVE_JOURNEY_MATCH_TRACE",
        "additionalHttpCalls" to 0, "searches" to searches.toList())

    override fun close() { ReflectionTestUtils.setField(calculator, "journeyMatcher", original) }
}
