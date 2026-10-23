package com.noLate.journey.application

import com.noLate.global.error.BusinessException
import com.noLate.global.error.ErrorCode

/** A static, server-owned crosswalk for exactly one saved subway segment. This is neither a
 * current train observation nor evidence that the passenger boarded or reached a station. */
data class JourneySubwayDeviceAdmission(
    val schemaVersion: Int = 1,
    val identityEvidence: String = "OFFICIAL_STATION_PATH_CROSSWALK",
    val subwayLegIndex: Int = 1,
    val providerRouteId: String,
    val startRoutingStationId: String,
    val endRoutingStationId: String,
    val stationCount: Int,
) {
    override fun toString() = "JourneySubwayDeviceAdmission(REDACTED)"
}

fun interface JourneySubwayDeviceAdmissionVerifier {
    fun verify(snapshot: JourneyRouteSnapshot): JourneySubwayDeviceAdmission
}

/** Only the live official two-path/coordinate catalog can construct this proof. */
internal class LiveJourneySubwayDeviceAdmissionVerifier(
    private val catalog: LiveOfficialSubwayServicePatternCatalog,
) : JourneySubwayDeviceAdmissionVerifier {
    override fun verify(snapshot: JourneyRouteSnapshot): JourneySubwayDeviceAdmission {
        val raw = JourneySubwaySavedLegEvidence.resolve(snapshot, 1).evidence ?: unavailable()
        if (snapshot.legs.map { it.kind } != listOf("WALK", "SUBWAY", "WALK")) unavailable()
        val pattern = try { catalog.resolveAll(snapshot, 1).singleOrNull() } catch (_: Exception) { null } ?: unavailable()
        val calls = pattern.stationCalls.subList(pattern.boardingIndex, pattern.alightingIndex + 1)
        if (pattern.officialStationNames == null || pattern.boardingIndex != 0 ||
            calls.size != raw.stations.size || calls.size !in 2..64 ||
            calls.any { !it.matches(Regex("[0-9]{4}")) } || calls.distinct().size != calls.size ||
            raw.startStationId == raw.endStationId) unavailable()
        return JourneySubwayDeviceAdmission(
            providerRouteId = "${raw.routingCity}:${raw.routingLine}",
            startRoutingStationId = raw.startStationId,
            endRoutingStationId = raw.endStationId,
            stationCount = calls.size - 1,
        )
    }

    private fun unavailable(): Nothing = throw BusinessException(
        ErrorCode.INVALID_STATE, "공식 지하철 역·정차 순서와 저장 경로를 대조할 수 없어 자동 안내를 준비하지 않습니다.")
}
