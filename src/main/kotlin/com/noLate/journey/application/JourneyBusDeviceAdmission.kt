package com.noLate.journey.application

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode
import com.noLate.transit.infrastructure.SeoulTransitArrivalClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/** One-shot, server-owned static route attestation before DEVICE preparation.
 * It does not assert current service, a particular vehicle, or passenger boarding. */
data class JourneyBusDeviceAdmission(
    val schemaVersion: Int = 1,
    val identityEvidence: String = "OFFICIAL_STOP_SHAPE_CROSSWALK",
    val busLegIndex: Int = 1,
    val routeId: String,
    val direction: String,
    val stationCount: Int,
    val firstStopSequence: Int,
    val lastStopSequence: Int,
) {
    override fun toString() = "JourneyBusDeviceAdmission(REDACTED)"
}

@Service
class JourneyBusDeviceAdmissionVerifier(
    private val provider: SeoulTransitArrivalClient,
    @Value("\${journey.automatic.official-bus-admission-enabled:false}") private val enabled: Boolean = false,
) {
    fun verify(snapshot: JourneyRouteSnapshot): JourneyBusDeviceAdmission {
        if (!enabled) unavailable()
        val raw = JourneyBusRouteBindingResolver().resolveRaw(snapshot)
        val routeId = raw.evidence?.routeId?.takeIf { it.matches(Regex("[1-9][0-9]{8}")) } ?: unavailable()
        val result = try {
            val catalog = provider.getBusRouteDiagnosticStops(routeId)
            if (catalog.isEmpty()) unavailable()
            JourneyBusRideMapping.resolve(snapshot, catalog, provider.getBusRoutePath(routeId))
        } catch (_: Exception) { unavailable() }
        val verified = result.verified ?: unavailable()
        return JourneyBusDeviceAdmission(routeId = verified.routeId, direction = verified.direction,
            stationCount = verified.stops.size - 1, firstStopSequence = verified.stops.first().sequence,
            lastStopSequence = verified.stops.last().sequence)
    }

    private fun unavailable(): Nothing = throw BusinessException(
        ErrorCode.INVALID_STATE, "공식 버스 노선과 저장 경로를 대조할 수 없어 자동 안내를 준비하지 않습니다.")
}
